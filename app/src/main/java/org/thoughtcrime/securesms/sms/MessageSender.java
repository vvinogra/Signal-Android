/*
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.sms;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Stream;

import org.greenrobot.eventbus.EventBus;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.attachments.AttachmentId;
import org.thoughtcrime.securesms.attachments.DatabaseAttachment;
import org.thoughtcrime.securesms.contacts.sync.ContactDiscovery;
import org.thoughtcrime.securesms.contactshare.Contact;
import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.database.MessageDatabase;
import org.thoughtcrime.securesms.database.MessageDatabase.SyncMessageId;
import org.thoughtcrime.securesms.database.MmsSmsDatabase;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.model.MessageId;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.MmsMessageRecord;
import org.thoughtcrime.securesms.database.model.ReactionRecord;
import org.thoughtcrime.securesms.database.model.SmsMessageRecord;
import org.thoughtcrime.securesms.database.model.StoryType;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobs.AttachmentCompressionJob;
import org.thoughtcrime.securesms.jobs.AttachmentCopyJob;
import org.thoughtcrime.securesms.jobs.AttachmentMarkUploadedJob;
import org.thoughtcrime.securesms.jobs.AttachmentUploadJob;
import org.thoughtcrime.securesms.jobs.MmsSendJob;
import org.thoughtcrime.securesms.jobs.ProfileKeySendJob;
import org.thoughtcrime.securesms.jobs.PushDistributionListSendJob;
import org.thoughtcrime.securesms.jobs.PushGroupSendJob;
import org.thoughtcrime.securesms.jobs.PushMediaSendJob;
import org.thoughtcrime.securesms.jobs.PushTextSendJob;
import org.thoughtcrime.securesms.jobs.ReactionSendJob;
import org.thoughtcrime.securesms.jobs.RemoteDeleteSendJob;
import org.thoughtcrime.securesms.jobs.ResumableUploadSpecJob;
import org.thoughtcrime.securesms.jobs.SmsSendJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.linkpreview.LinkPreview;
import org.thoughtcrime.securesms.mediasend.Media;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.OutgoingMediaMessage;
import org.thoughtcrime.securesms.mms.OutgoingSecureMediaMessage;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.service.ExpiringMessageManager;
import org.thoughtcrime.securesms.util.ParcelUtil;
import org.thoughtcrime.securesms.util.SignalLocalMetrics;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.push.DistributionId;
import org.whispersystems.signalservice.api.util.Preconditions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class MessageSender {

  private static final String TAG = Log.tag(MessageSender.class);

  /**
   * Suitable for a 1:1 conversation or a GV1 group only.
   */
  @WorkerThread
  public static void sendProfileKey(final long threadId) {
    ProfileKeySendJob job = ProfileKeySendJob.create(threadId, false);
    if (job != null) {
      ApplicationDependencies.getJobManager().add(job);
    }
  }

  public static long send(final Context context,
                          final OutgoingTextMessage message,
                          final long threadId,
                          final boolean forceSms,
                          @Nullable final String metricId,
                          final SmsDatabase.InsertListener insertListener)
  {
    Log.i(TAG, "Sending text message to " + message.getRecipient().getId() + ", thread: " + threadId);
    MessageDatabase database    = SignalDatabase.sms();
    Recipient       recipient   = message.getRecipient();
    boolean         keyExchange = message.isKeyExchange();

    long allocatedThreadId = SignalDatabase.threads().getOrCreateValidThreadId(recipient, threadId);
    long messageId         = database.insertMessageOutbox(allocatedThreadId,
                                                          applyUniversalExpireTimerIfNecessary(context, recipient, message, allocatedThreadId),
                                                          forceSms,
                                                          System.currentTimeMillis(),
                                                          insertListener);

    SignalLocalMetrics.IndividualMessageSend.onInsertedIntoDatabase(messageId, metricId);

    sendTextMessage(context, recipient, forceSms, keyExchange, messageId);
    onMessageSent();
    SignalDatabase.threads().update(threadId, true);

    return allocatedThreadId;
  }

  public static void sendStories(@NonNull final Context context,
                                 @NonNull final List<OutgoingSecureMediaMessage> messages,
                                 @Nullable final String metricId,
                                 @Nullable final SmsDatabase.InsertListener insertListener)
  {
    Log.i(TAG, "Sending story messages to " + messages.size() + " targets.");
    ThreadDatabase        threadDatabase  = SignalDatabase.threads();
    MessageDatabase       database        = SignalDatabase.mms();
    List<Long>            messageIds      = new ArrayList<>(messages.size());
    List<Long>            threads         = new ArrayList<>(messages.size());
    UploadDependencyGraph dependencyGraph;

    try {
      database.beginTransaction();

      for (OutgoingSecureMediaMessage message : messages) {
        long      allocatedThreadId = threadDatabase.getOrCreateValidThreadId(message.getRecipient(), -1L, message.getDistributionType());
        Recipient recipient         = message.getRecipient();
        long      messageId         = database.insertMessageOutbox(applyUniversalExpireTimerIfNecessary(context, recipient, message.stripAttachments(), allocatedThreadId), allocatedThreadId, false, insertListener);

        messageIds.add(messageId);
        threads.add(allocatedThreadId);

        if (message.getRecipient().isGroup() && message.getAttachments().isEmpty() && message.getLinkPreviews().isEmpty() && message.getSharedContacts().isEmpty()) {
          SignalLocalMetrics.GroupMessageSend.onInsertedIntoDatabase(messageId, metricId);
        } else {
          SignalLocalMetrics.GroupMessageSend.cancel(metricId);
        }
      }

      for (int i = 0; i < messageIds.size(); i++) {
        long                       messageId = messageIds.get(i);
        OutgoingSecureMediaMessage message   = messages.get(i);
        Recipient                  recipient = message.getRecipient();

        if (recipient.isDistributionList()) {
          DistributionId    distributionId = Objects.requireNonNull(SignalDatabase.distributionLists().getDistributionId(recipient.requireDistributionListId()));
          List<RecipientId> members        = SignalDatabase.distributionLists().getMembers(recipient.requireDistributionListId());
          SignalDatabase.storySends().insert(messageId, members, message.getSentTimeMillis(), message.getStoryType().isStoryWithReplies(), distributionId);
        }
      }

      dependencyGraph = UploadDependencyGraph.create(
          messages,
          ApplicationDependencies.getJobManager(),
          attachment -> {
            try {
              return SignalDatabase.attachments().insertAttachmentForPreUpload(attachment);
            } catch (MmsException e) {
              Log.e(TAG, e);
              throw new IllegalStateException(e);
            }
          }
      );

      for (int i = 0; i < messageIds.size(); i++) {
        long                             messageId = messageIds.get(i);
        OutgoingSecureMediaMessage       message   = messages.get(i);
        List<UploadDependencyGraph.Node> nodes     = dependencyGraph.getDependencyMap().get(message);

        if (nodes == null || nodes.isEmpty()) {
          if (message.getStoryType().isTextStory()) {
            Log.d(TAG, "No attachments for given text story. Skipping.");
            continue;
          } else {
            Log.e(TAG, "No attachments for given media story. Aborting.");
            throw new MmsException("No attachment for story.");
          }
        }

        List<AttachmentId> attachmentIds = nodes.stream().map(UploadDependencyGraph.Node::getAttachmentId).collect(Collectors.toList());
        SignalDatabase.attachments().updateMessageId(attachmentIds, messageId, true);
        for (final AttachmentId attachmentId : attachmentIds) {
          SignalDatabase.attachments().updateAttachmentCaption(attachmentId, message.getBody());
        }
      }

      database.setTransactionSuccessful();
    } catch (MmsException e) {
      Log.w(TAG, "Failed to send stories.", e);
      return;
    } finally {
      database.endTransaction();
    }

    List<JobManager.Chain> chains = dependencyGraph.consumeDeferredQueue();
    for (final JobManager.Chain chain : chains) {
      chain.enqueue();
    }

    for (int i = 0; i < messageIds.size(); i++) {
      long                             messageId    = messageIds.get(i);
      OutgoingSecureMediaMessage       message      = messages.get(i);
      Recipient                        recipient    = message.getRecipient();
      List<UploadDependencyGraph.Node> dependencies = dependencyGraph.getDependencyMap().get(message);

      List<String> jobDependencyIds = (dependencies != null) ? dependencies.stream().map(UploadDependencyGraph.Node::getJobId).collect(Collectors.toList())
                                                             : Collections.emptyList();

      sendMediaMessage(context,
                       recipient,
                       false,
                       messageId,
                       jobDependencyIds);
    }

    onMessageSent();

    for (long threadId : threads) {
      threadDatabase.update(threadId, true);
    }
  }

  public static long send(final Context context,
                          final OutgoingMediaMessage message,
                          final long threadId,
                          final boolean forceSms,
                          @Nullable final String metricId,
                          final SmsDatabase.InsertListener insertListener)
  {
    Log.i(TAG, "Sending media message to " + message.getRecipient().getId() + ", thread: " + threadId);
    try {
      ThreadDatabase  threadDatabase = SignalDatabase.threads();
      MessageDatabase database       = SignalDatabase.mms();

      long      allocatedThreadId = threadDatabase.getOrCreateValidThreadId(message.getRecipient(), threadId, message.getDistributionType());
      Recipient recipient         = message.getRecipient();
      long      messageId         = database.insertMessageOutbox(applyUniversalExpireTimerIfNecessary(context, recipient, message, allocatedThreadId), allocatedThreadId, forceSms, insertListener);

      if (message.getRecipient().isGroup() && message.getAttachments().isEmpty() && message.getLinkPreviews().isEmpty() && message.getSharedContacts().isEmpty()) {
        SignalLocalMetrics.GroupMessageSend.onInsertedIntoDatabase(messageId, metricId);
      } else {
        SignalLocalMetrics.GroupMessageSend.cancel(metricId);
      }

      sendMediaMessage(context, recipient, forceSms, messageId, Collections.emptyList());
      onMessageSent();
      threadDatabase.update(threadId, true);

      return allocatedThreadId;
    } catch (MmsException e) {
      Log.w(TAG, e);
      return threadId;
    }
  }

  public static long sendPushWithPreUploadedMedia(final Context context,
                                                  final OutgoingMediaMessage message,
                                                  final Collection<PreUploadResult> preUploadResults,
                                                  final long threadId,
                                                  final SmsDatabase.InsertListener insertListener)
  {
    Log.i(TAG, "Sending media message with pre-uploads to " + message.getRecipient().getId() + ", thread: " + threadId + ", pre-uploads: " +  preUploadResults);
    Preconditions.checkArgument(message.getAttachments().isEmpty(), "If the media is pre-uploaded, there should be no attachments on the message.");

    try {
      ThreadDatabase     threadDatabase     = SignalDatabase.threads();
      MessageDatabase    mmsDatabase        = SignalDatabase.mms();
      AttachmentDatabase attachmentDatabase = SignalDatabase.attachments();

      long allocatedThreadId;

      if (threadId == -1) {
        allocatedThreadId = threadDatabase.getOrCreateThreadIdFor(message.getRecipient(), message.getDistributionType());
      } else {
        allocatedThreadId = threadId;
      }

      Recipient recipient = message.getRecipient();
      long      messageId = mmsDatabase.insertMessageOutbox(applyUniversalExpireTimerIfNecessary(context, recipient, message, allocatedThreadId),
                                                            allocatedThreadId,
                                                            false,
                                                            insertListener);

      List<AttachmentId> attachmentIds = Stream.of(preUploadResults).map(PreUploadResult::getAttachmentId).toList();
      List<String>       jobIds        = Stream.of(preUploadResults).map(PreUploadResult::getJobIds).flatMap(Stream::of).toList();

      attachmentDatabase.updateMessageId(attachmentIds, messageId, message.getStoryType().isStory());

      sendMediaMessage(context, recipient, false, messageId, jobIds);
      onMessageSent();
      threadDatabase.update(threadId, true);

      return allocatedThreadId;
    } catch (MmsException e) {
      Log.w(TAG, e);
      return threadId;
    }
  }

  public static void sendMediaBroadcast(@NonNull Context context,
                                        @NonNull List<OutgoingSecureMediaMessage> messages,
                                        @NonNull Collection<PreUploadResult> preUploadResults,
                                        boolean overwritePreUploadMessageIds)
  {
    Log.i(TAG, "Sending media broadcast (overwrite: " + overwritePreUploadMessageIds + ") to " + Stream.of(messages).map(m -> m.getRecipient().getId()).toList());
    Preconditions.checkArgument(messages.size() > 0, "No messages!");
    Preconditions.checkArgument(Stream.of(messages).allMatch(m -> m.getAttachments().isEmpty()), "Messages can't have attachments! They should be pre-uploaded.");

    JobManager                 jobManager             = ApplicationDependencies.getJobManager();
    AttachmentDatabase         attachmentDatabase     = SignalDatabase.attachments();
    MessageDatabase            mmsDatabase            = SignalDatabase.mms();
    ThreadDatabase             threadDatabase         = SignalDatabase.threads();
    List<AttachmentId>         preUploadAttachmentIds = Stream.of(preUploadResults).map(PreUploadResult::getAttachmentId).toList();
    List<String>               preUploadJobIds        = Stream.of(preUploadResults).map(PreUploadResult::getJobIds).flatMap(Stream::of).toList();
    List<Long>                 messageIds             = new ArrayList<>(messages.size());
    List<String>               messageDependsOnIds    = new ArrayList<>(preUploadJobIds);
    OutgoingSecureMediaMessage primaryMessage         = messages.get(0);

    mmsDatabase.beginTransaction();
    try {
      if (overwritePreUploadMessageIds) {
        long primaryThreadId  = threadDatabase.getOrCreateThreadIdFor(primaryMessage.getRecipient(), primaryMessage.getDistributionType());
        long primaryMessageId = mmsDatabase.insertMessageOutbox(applyUniversalExpireTimerIfNecessary(context, primaryMessage.getRecipient(), primaryMessage, primaryThreadId),
                                                                primaryThreadId,
                                                                false,
                                                                null);

        attachmentDatabase.updateMessageId(preUploadAttachmentIds, primaryMessageId, primaryMessage.getStoryType().isStory());
        if (primaryMessage.getStoryType() != StoryType.NONE) {
          for (final AttachmentId preUploadAttachmentId : preUploadAttachmentIds) {
            attachmentDatabase.updateAttachmentCaption(preUploadAttachmentId, primaryMessage.getBody());
          }
        }
        messageIds.add(primaryMessageId);
      }

      List<DatabaseAttachment> preUploadAttachments = Stream.of(preUploadAttachmentIds)
                                                            .map(attachmentDatabase::getAttachment)
                                                            .toList();

      if (messages.size() > 0) {
        List<OutgoingSecureMediaMessage> secondaryMessages = overwritePreUploadMessageIds ? messages.subList(1, messages.size()) : messages;
        List<List<AttachmentId>>         attachmentCopies  = new ArrayList<>();

        for (int i = 0; i < preUploadAttachmentIds.size(); i++) {
          attachmentCopies.add(new ArrayList<>(messages.size()));
        }

        for (OutgoingSecureMediaMessage secondaryMessage : secondaryMessages) {
          long               allocatedThreadId = threadDatabase.getOrCreateThreadIdFor(secondaryMessage.getRecipient(), secondaryMessage.getDistributionType());
          long               messageId         = mmsDatabase.insertMessageOutbox(applyUniversalExpireTimerIfNecessary(context, secondaryMessage.getRecipient(), secondaryMessage, allocatedThreadId),
                                                                                 allocatedThreadId,
                                                                                 false,
                                                                                 null);
          List<AttachmentId> attachmentIds     = new ArrayList<>(preUploadAttachmentIds.size());

          for (int i = 0; i < preUploadAttachments.size(); i++) {
            AttachmentId attachmentId = attachmentDatabase.insertAttachmentForPreUpload(preUploadAttachments.get(i)).getAttachmentId();
            attachmentCopies.get(i).add(attachmentId);
            attachmentIds.add(attachmentId);
          }

          attachmentDatabase.updateMessageId(attachmentIds, messageId, secondaryMessage.getStoryType().isStory());
          if (primaryMessage.getStoryType() != StoryType.NONE) {
            for (final AttachmentId preUploadAttachmentId : attachmentIds) {
              attachmentDatabase.updateAttachmentCaption(preUploadAttachmentId, primaryMessage.getBody());
            }
          }

          messageIds.add(messageId);
        }

        for (int i = 0; i < attachmentCopies.size(); i++) {
          Job copyJob = new AttachmentCopyJob(preUploadAttachmentIds.get(i), attachmentCopies.get(i));
          jobManager.add(copyJob, preUploadJobIds);
          messageDependsOnIds.add(copyJob.getId());
        }
      }

      for (int i = 0; i < messageIds.size(); i++) {
        long                       messageId = messageIds.get(i);
        OutgoingSecureMediaMessage message   = messages.get(i);
        Recipient                  recipient = message.getRecipient();

        if (recipient.isDistributionList()) {
          List<RecipientId> members        = SignalDatabase.distributionLists().getMembers(recipient.requireDistributionListId());
          DistributionId    distributionId = Objects.requireNonNull(SignalDatabase.distributionLists().getDistributionId(recipient.requireDistributionListId()));
          SignalDatabase.storySends().insert(messageId, members, message.getSentTimeMillis(), message.getStoryType().isStoryWithReplies(), distributionId);
        }
      }

      onMessageSent();
      mmsDatabase.setTransactionSuccessful();
    } catch (MmsException e) {
      Log.w(TAG, "Failed to send messages.", e);
      return;
    } finally {
      mmsDatabase.endTransaction();
    }

    for (int i = 0; i < messageIds.size(); i++) {
      long      messageId = messageIds.get(i);
      Recipient recipient = messages.get(i).getRecipient();

      if (isLocalSelfSend(context, recipient, false)) {
        sendLocalMediaSelf(context, messageId);
      } else if (recipient.isPushGroup()) {
        jobManager.add(new PushGroupSendJob(messageId, recipient.getId(), Collections.emptySet(), true), messageDependsOnIds, recipient.getId().toQueueKey());
      } else if (recipient.isDistributionList()) {
        jobManager.add(new PushDistributionListSendJob(messageId, recipient.getId(), true, Collections.emptySet()), messageDependsOnIds, recipient.getId().toQueueKey());
      } else {
        jobManager.add(new PushMediaSendJob(messageId, recipient, true), messageDependsOnIds, recipient.getId().toQueueKey());
      }
    }
  }

  /**
   * @return A result if the attachment was enqueued, or null if it failed to enqueue or shouldn't
   *         be enqueued (like in the case of a local self-send).
   */
  public static @Nullable PreUploadResult preUploadPushAttachment(@NonNull Context context, @NonNull Attachment attachment, @Nullable Recipient recipient, @NonNull Media media) {
    if (isLocalSelfSend(context, recipient, false)) {
      return null;
    }
    Log.i(TAG, "Pre-uploading attachment for " + (recipient != null ? recipient.getId() : "null"));

    try {
      AttachmentDatabase attachmentDatabase = SignalDatabase.attachments();
      DatabaseAttachment databaseAttachment = attachmentDatabase.insertAttachmentForPreUpload(attachment);

      Job compressionJob         = AttachmentCompressionJob.fromAttachment(databaseAttachment, false, -1);
      Job resumableUploadSpecJob = new ResumableUploadSpecJob();
      Job uploadJob              = new AttachmentUploadJob(databaseAttachment.getAttachmentId());

      ApplicationDependencies.getJobManager()
                             .startChain(compressionJob)
                             .then(resumableUploadSpecJob)
                             .then(uploadJob)
                             .enqueue();

      return new PreUploadResult(media, databaseAttachment.getAttachmentId(), Arrays.asList(compressionJob.getId(), resumableUploadSpecJob.getId(), uploadJob.getId()));
    } catch (MmsException e) {
      Log.w(TAG, "preUploadPushAttachment() - Failed to upload!", e);
      return null;
    }
  }

  public static void sendNewReaction(@NonNull Context context, @NonNull MessageId messageId, @NonNull String emoji) {
    ReactionRecord reaction = new ReactionRecord(emoji, Recipient.self().getId(), System.currentTimeMillis(), System.currentTimeMillis());
    SignalDatabase.reactions().addReaction(messageId, reaction);

    try {
      ApplicationDependencies.getJobManager().add(ReactionSendJob.create(context, messageId, reaction, false));
      onMessageSent();
    } catch (NoSuchMessageException e) {
      Log.w(TAG, "[sendNewReaction] Could not find message! Ignoring.");
    }
  }

  public static void sendReactionRemoval(@NonNull Context context, @NonNull MessageId messageId, @NonNull ReactionRecord reaction) {
    SignalDatabase.reactions().deleteReaction(messageId, reaction.getAuthor());

    try {
      ApplicationDependencies.getJobManager().add(ReactionSendJob.create(context, messageId, reaction, true));
      onMessageSent();
    } catch (NoSuchMessageException e) {
      Log.w(TAG, "[sendReactionRemoval] Could not find message! Ignoring.");
    }
  }

  public static void sendRemoteDelete(long messageId, boolean isMms) {
    MessageDatabase db = isMms ? SignalDatabase.mms() : SignalDatabase.sms();
    db.markAsRemoteDelete(messageId);
    db.markAsSending(messageId);

    try {
      RemoteDeleteSendJob.create(messageId, isMms).enqueue();
      onMessageSent();
    } catch (NoSuchMessageException e) {
      Log.w(TAG, "[sendRemoteDelete] Could not find message! Ignoring.");
    }
  }

  public static void resendGroupMessage(@NonNull Context context, @NonNull MessageRecord messageRecord, @NonNull Set<RecipientId> filterRecipientIds) {
    if (!messageRecord.isMms()) throw new AssertionError("Not Group");
    sendGroupPush(context, messageRecord.getRecipient(), messageRecord.getId(), filterRecipientIds, Collections.emptyList());
    onMessageSent();
  }

  public static void resendDistributionList(@NonNull Context context, @NonNull MessageRecord messageRecord, @NonNull Set<RecipientId> filterRecipientIds) {
    if (!messageRecord.isMms() && !((MmsMessageRecord) messageRecord).getStoryType().isStory()) {
      throw new AssertionError("Not a story");
    }
    sendDistributionList(context, messageRecord.getRecipient(), messageRecord.getId(), filterRecipientIds, Collections.emptyList());
    onMessageSent();
  }

  public static void resend(Context context, MessageRecord messageRecord) {
    long       messageId   = messageRecord.getId();
    boolean    forceSms    = messageRecord.isForcedSms();
    boolean    keyExchange = messageRecord.isKeyExchange();
    Recipient  recipient   = messageRecord.getRecipient();

    if (messageRecord.isMms()) {
      sendMediaMessage(context, recipient, forceSms, messageId, Collections.emptyList());
    } else {
      sendTextMessage(context, recipient, forceSms, keyExchange, messageId);
    }

    onMessageSent();
  }

  public static void onMessageSent() {
    EventBus.getDefault().postSticky(MessageSentEvent.INSTANCE);
  }

  private static @NonNull OutgoingTextMessage applyUniversalExpireTimerIfNecessary(@NonNull Context context, @NonNull Recipient recipient, @NonNull OutgoingTextMessage outgoingTextMessage, long threadId) {
    if (outgoingTextMessage.getExpiresIn() == 0 && RecipientUtil.setAndSendUniversalExpireTimerIfNecessary(context, recipient, threadId)) {
      return outgoingTextMessage.withExpiry(TimeUnit.SECONDS.toMillis(SignalStore.settings().getUniversalExpireTimer()));
    }
    return outgoingTextMessage;
  }

  private static @NonNull OutgoingMediaMessage applyUniversalExpireTimerIfNecessary(@NonNull Context context, @NonNull Recipient recipient, @NonNull OutgoingMediaMessage outgoingMediaMessage, long threadId) {
    if (!outgoingMediaMessage.isExpirationUpdate() && outgoingMediaMessage.getExpiresIn() == 0 && RecipientUtil.setAndSendUniversalExpireTimerIfNecessary(context, recipient, threadId)) {
      return outgoingMediaMessage.withExpiry(TimeUnit.SECONDS.toMillis(SignalStore.settings().getUniversalExpireTimer()));
    }
    return outgoingMediaMessage;
  }

  private static void sendMediaMessage(Context context, Recipient recipient, boolean forceSms, long messageId, @NonNull Collection<String> uploadJobIds)
  {
    if (isLocalSelfSend(context, recipient, forceSms)) {
      sendLocalMediaSelf(context, messageId);
    } else if (recipient.isPushGroup()) {
      sendGroupPush(context, recipient, messageId, Collections.emptySet(), uploadJobIds);
    } else if (recipient.isDistributionList()) {
      sendDistributionList(context, recipient, messageId, Collections.emptySet(), uploadJobIds);
    } else if (!forceSms && isPushMediaSend(context, recipient)) {
      sendMediaPush(context, recipient, messageId, uploadJobIds);
    } else {
      sendMms(context, messageId);
    }
  }

  private static void sendTextMessage(Context context, Recipient recipient,
                                      boolean forceSms, boolean keyExchange,
                                      long messageId)
  {
    if (isLocalSelfSend(context, recipient, forceSms)) {
      sendLocalTextSelf(context, messageId);
    } else if (!forceSms && isPushTextSend(context, recipient, keyExchange)) {
      sendTextPush(recipient, messageId);
    } else {
      sendSms(recipient, messageId);
    }
  }

  private static void sendTextPush(Recipient recipient, long messageId) {
    ApplicationDependencies.getJobManager().add(new PushTextSendJob(messageId, recipient));
  }

  private static void sendMediaPush(Context context, Recipient recipient, long messageId, @NonNull Collection<String> uploadJobIds) {
    JobManager jobManager = ApplicationDependencies.getJobManager();

    if (uploadJobIds.size() > 0) {
      Job mediaSend = new PushMediaSendJob(messageId, recipient, true);
      jobManager.add(mediaSend, uploadJobIds);
    } else {
      PushMediaSendJob.enqueue(context, jobManager, messageId, recipient);
    }
  }

  private static void sendGroupPush(@NonNull Context context, @NonNull Recipient recipient, long messageId, @NonNull Set<RecipientId> filterRecipientIds, @NonNull Collection<String> uploadJobIds) {
    JobManager jobManager = ApplicationDependencies.getJobManager();

    if (uploadJobIds.size() > 0) {
      Job groupSend = new PushGroupSendJob(messageId, recipient.getId(), filterRecipientIds, !uploadJobIds.isEmpty());
      jobManager.add(groupSend, uploadJobIds, uploadJobIds.isEmpty() ? null : recipient.getId().toQueueKey());
    } else {
      PushGroupSendJob.enqueue(context, jobManager, messageId, recipient.getId(), filterRecipientIds);
    }
  }

  private static void sendDistributionList(@NonNull Context context, @NonNull Recipient recipient, long messageId, @NonNull Set<RecipientId> filterRecipientIds, @NonNull Collection<String> uploadJobIds) {
    JobManager jobManager = ApplicationDependencies.getJobManager();

    if (uploadJobIds.size() > 0) {
      Job groupSend = new PushDistributionListSendJob(messageId, recipient.getId(), !uploadJobIds.isEmpty(), filterRecipientIds);
      jobManager.add(groupSend, uploadJobIds, uploadJobIds.isEmpty() ? null : recipient.getId().toQueueKey());
    } else {
      PushDistributionListSendJob.enqueue(context, jobManager, messageId, recipient.getId(), filterRecipientIds);
    }
  }

  private static void sendSms(Recipient recipient, long messageId) {
    JobManager jobManager = ApplicationDependencies.getJobManager();
    jobManager.add(new SmsSendJob(messageId, recipient));
  }

  private static void sendMms(Context context, long messageId) {
    JobManager jobManager = ApplicationDependencies.getJobManager();
    MmsSendJob.enqueue(context, jobManager, messageId);
  }

  private static boolean isPushTextSend(Context context, Recipient recipient, boolean keyExchange) {
    if (!SignalStore.account().isRegistered()) {
      return false;
    }

    if (keyExchange) {
      return false;
    }

    return isPushDestination(context, recipient);
  }

  private static boolean isPushMediaSend(Context context, Recipient recipient) {
    if (!SignalStore.account().isRegistered()) {
      return false;
    }

    if (recipient.isGroup()) {
      return false;
    }

    return isPushDestination(context, recipient);
  }

  private static boolean isPushDestination(Context context, Recipient destination) {
    if (destination.resolve().getRegistered() == RecipientDatabase.RegisteredState.REGISTERED) {
      return true;
    } else if (destination.resolve().getRegistered() == RecipientDatabase.RegisteredState.NOT_REGISTERED) {
      return false;
    } else {
      try {
        RecipientDatabase.RegisteredState state = ContactDiscovery.refresh(context, destination, false);
        return state == RecipientDatabase.RegisteredState.REGISTERED;
      } catch (IOException e1) {
        Log.w(TAG, e1);
        return false;
      }
    }
  }

  public static boolean isLocalSelfSend(@NonNull Context context, @Nullable Recipient recipient, boolean forceSms) {
    return recipient != null                    &&
           recipient.isSelf()                   &&
           !forceSms                            &&
           SignalStore.account().isRegistered() &&
           !TextSecurePreferences.isMultiDevice(context);
  }

  private static void sendLocalMediaSelf(Context context, long messageId) {
    try {
      ExpiringMessageManager expirationManager  = ApplicationDependencies.getExpiringMessageManager();
      MessageDatabase        mmsDatabase        = SignalDatabase.mms();
      MmsSmsDatabase         mmsSmsDatabase     = SignalDatabase.mmsSms();
      OutgoingMediaMessage   message            = mmsDatabase.getOutgoingMessage(messageId);
      SyncMessageId          syncId             = new SyncMessageId(Recipient.self().getId(), message.getSentTimeMillis());
      List<Attachment>       attachments        = new LinkedList<>();


      attachments.addAll(message.getAttachments());

      attachments.addAll(Stream.of(message.getLinkPreviews())
                               .map(LinkPreview::getThumbnail)
                               .filter(Optional::isPresent)
                               .map(Optional::get)
                               .toList());

      attachments.addAll(Stream.of(message.getSharedContacts())
                               .map(Contact::getAvatar).withoutNulls()
                               .map(Contact.Avatar::getAttachment).withoutNulls()
                               .toList());

      List<AttachmentCompressionJob> compressionJobs = Stream.of(attachments)
                                                             .map(a -> AttachmentCompressionJob.fromAttachment((DatabaseAttachment) a, false, -1))
                                                             .toList();

      List<AttachmentMarkUploadedJob> fakeUploadJobs = Stream.of(attachments)
                                                             .map(a -> new AttachmentMarkUploadedJob(messageId, ((DatabaseAttachment) a).getAttachmentId()))
                                                             .toList();

      ApplicationDependencies.getJobManager().startChain(compressionJobs)
                                             .then(fakeUploadJobs)
                                             .enqueue();

      mmsDatabase.markAsSent(messageId, true);
      mmsDatabase.markUnidentified(messageId, true);

      mmsSmsDatabase.incrementDeliveryReceiptCount(syncId, System.currentTimeMillis());
      mmsSmsDatabase.incrementReadReceiptCount(syncId, System.currentTimeMillis());
      mmsSmsDatabase.incrementViewedReceiptCount(syncId, System.currentTimeMillis());

      if (message.getExpiresIn() > 0 && !message.isExpirationUpdate()) {
        mmsDatabase.markExpireStarted(messageId);
        expirationManager.scheduleDeletion(messageId, true, message.getExpiresIn());
      }
    } catch (NoSuchMessageException | MmsException e) {
      Log.w(TAG, "Failed to update self-sent message.", e);
    }
  }

  private static void sendLocalTextSelf(Context context, long messageId) {
    try {
      ExpiringMessageManager expirationManager = ApplicationDependencies.getExpiringMessageManager();
      MessageDatabase        smsDatabase       = SignalDatabase.sms();
      MmsSmsDatabase         mmsSmsDatabase    = SignalDatabase.mmsSms();
      SmsMessageRecord       message           = smsDatabase.getSmsMessage(messageId);
      SyncMessageId          syncId            = new SyncMessageId(Recipient.self().getId(), message.getDateSent());

      smsDatabase.markAsSent(messageId, true);
      smsDatabase.markUnidentified(messageId, true);

      mmsSmsDatabase.incrementDeliveryReceiptCount(syncId, System.currentTimeMillis());
      mmsSmsDatabase.incrementReadReceiptCount(syncId, System.currentTimeMillis());

      if (message.getExpiresIn() > 0) {
        smsDatabase.markExpireStarted(messageId);
        expirationManager.scheduleDeletion(message.getId(), message.isMms(), message.getExpiresIn());
      }
    } catch (NoSuchMessageException e) {
      Log.w(TAG, "Failed to update self-sent message.", e);
    }
  }

  public static class PreUploadResult implements Parcelable {
    private final Media              media;
    private final AttachmentId       attachmentId;
    private final Collection<String> jobIds;

    PreUploadResult(@NonNull Media media, @NonNull AttachmentId attachmentId, @NonNull Collection<String> jobIds) {
      this.media        = media;
      this.attachmentId = attachmentId;
      this.jobIds       = jobIds;
    }

    private PreUploadResult(Parcel in) {
      this.attachmentId = in.readParcelable(AttachmentId.class.getClassLoader());
      this.jobIds       = ParcelUtil.readStringCollection(in);
      this.media        = in.readParcelable(Media.class.getClassLoader());
    }

    public @NonNull AttachmentId getAttachmentId() {
      return attachmentId;
    }

    public @NonNull Collection<String> getJobIds() {
      return jobIds;
    }

    public @NonNull Media getMedia() {
      return media;
    }

    public static final Creator<PreUploadResult> CREATOR = new Creator<PreUploadResult>() {
      @Override
      public PreUploadResult createFromParcel(Parcel in) {
        return new PreUploadResult(in);
      }

      @Override
      public PreUploadResult[] newArray(int size) {
        return new PreUploadResult[size];
      }
    };

    @Override
    public int describeContents() {
      return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
      dest.writeParcelable(attachmentId, flags);
      ParcelUtil.writeStringCollection(dest, jobIds);
      dest.writeParcelable(media, flags);
    }

    @Override
    public @NonNull String toString() {
      return "{ID: " + attachmentId.getRowId() + ", URI: " + media.getUri() + ", Jobs: " + jobIds.stream().map(j -> "JOB::" + j).collect(Collectors.toList()) + "}";
    }
  }

  public enum MessageSentEvent {
    INSTANCE
  }
}
