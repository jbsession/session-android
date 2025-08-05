/*
 * Copyright (C) 2011 Whisper Systems
 * Copyright (C) 2013-2017 Open Whisper Systems
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
package org.thoughtcrime.securesms.database;

import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_ID;
import static org.thoughtcrime.securesms.database.UtilKt.generatePlaceholders;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import com.annimon.stream.Stream;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.json.JSONArray;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.session.libsession.messaging.sending_receiving.notifications.MessageNotifier;
import org.session.libsession.snode.SnodeAPI;
import org.session.libsession.utilities.Address;
import org.session.libsession.utilities.AddressKt;
import org.session.libsession.utilities.ConfigFactoryProtocol;
import org.session.libsession.utilities.ConfigFactoryProtocolKt;
import org.session.libsession.utilities.DistributionTypes;
import org.session.libsession.utilities.GroupUtil;
import org.session.libsession.utilities.TextSecurePreferences;
import org.session.libsession.utilities.Util;
import org.session.libsession.utilities.recipients.Recipient;
import org.session.libsignal.utilities.AccountId;
import org.session.libsignal.utilities.Log;
import org.session.libsignal.utilities.Pair;
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;
import org.thoughtcrime.securesms.database.model.GroupThreadStatus;
import org.thoughtcrime.securesms.database.model.MediaMmsMessageRecord;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.MmsMessageRecord;
import org.thoughtcrime.securesms.database.model.ThreadRecord;
import org.thoughtcrime.securesms.database.model.content.MessageContent;
import org.thoughtcrime.securesms.mms.Slide;
import org.thoughtcrime.securesms.mms.SlideDeck;
import org.thoughtcrime.securesms.notifications.MarkReadReceiver;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import dagger.Lazy;
import kotlin.collections.CollectionsKt;
import kotlinx.coroutines.channels.BufferOverflow;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.MutableSharedFlow;
import kotlinx.coroutines.flow.SharedFlowKt;
import kotlinx.serialization.json.Json;
import network.loki.messenger.libsession_util.util.GroupInfo;

@Singleton
public class ThreadDatabase extends Database {

  public interface ConversationThreadUpdateListener {
    void threadCreated(@NonNull Address address, long threadId);
  }

  private static final String TAG = ThreadDatabase.class.getSimpleName();

  // Map of threadID -> Address

  public  static final String TABLE_NAME             = "thread";
  public  static final String ID                     = "_id";
  public  static final String THREAD_CREATION_DATE   = "date";
  public  static final String MESSAGE_COUNT          = "message_count";
  public  static final String ADDRESS                = "recipient_ids";
  public  static final String SNIPPET                = "snippet";
  private static final String SNIPPET_CHARSET        = "snippet_cs";
  public  static final String READ                   = "read";
  public  static final String UNREAD_COUNT           = "unread_count";
  public  static final String UNREAD_MENTION_COUNT   = "unread_mention_count";
  @Deprecated(forRemoval = true)
  public  static final String DISTRIBUTION_TYPE      = "type"; // See: DistributionTypes.kt
  private static final String ERROR                  = "error";
  public  static final String SNIPPET_TYPE           = "snippet_type";
  @Deprecated(forRemoval = true)
  public  static final String SNIPPET_URI            = "snippet_uri";
  @Deprecated(forRemoval = true)
  /**
   * The column that hold a {@link MessageContent}. See {@link MmsDatabase#MESSAGE_CONTENT} for more information
   */
  public  static final String SNIPPET_CONTENT        = "snippet_content";
  public  static final String ARCHIVED               = "archived";
  public  static final String STATUS                 = "status";
  public  static final String DELIVERY_RECEIPT_COUNT = "delivery_receipt_count";
  public  static final String READ_RECEIPT_COUNT     = "read_receipt_count";
  @Deprecated(forRemoval = true)
  public  static final String EXPIRES_IN             = "expires_in";
  public  static final String LAST_SEEN              = "last_seen";
  public static final String HAS_SENT                = "has_sent";

  @Deprecated(forRemoval = true)
  public  static final String IS_PINNED              = "is_pinned";

  public static final String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME + " ("                    +
    ID + " INTEGER PRIMARY KEY, " + THREAD_CREATION_DATE + " INTEGER DEFAULT 0, "                  +
    MESSAGE_COUNT + " INTEGER DEFAULT 0, " + ADDRESS + " TEXT, " + SNIPPET + " TEXT, "             +
    SNIPPET_CHARSET + " INTEGER DEFAULT 0, " + READ + " INTEGER DEFAULT 1, "                       +
          DISTRIBUTION_TYPE + " INTEGER DEFAULT 0, " + ERROR + " INTEGER DEFAULT 0, "                    +
    SNIPPET_TYPE + " INTEGER DEFAULT 0, " + SNIPPET_URI + " TEXT DEFAULT NULL, "                   +
    ARCHIVED + " INTEGER DEFAULT 0, " + STATUS + " INTEGER DEFAULT 0, "                            +
    DELIVERY_RECEIPT_COUNT + " INTEGER DEFAULT 0, " + EXPIRES_IN + " INTEGER DEFAULT 0, "          +
    LAST_SEEN + " INTEGER DEFAULT 0, " + HAS_SENT + " INTEGER DEFAULT 0, "                         +
    READ_RECEIPT_COUNT + " INTEGER DEFAULT 0, " + UNREAD_COUNT + " INTEGER DEFAULT 0);";

  public static final String[] CREATE_INDEXES = {
    "CREATE INDEX IF NOT EXISTS thread_recipient_ids_index ON " + TABLE_NAME + " (" + ADDRESS + ");",
    "CREATE INDEX IF NOT EXISTS archived_count_index ON " + TABLE_NAME + " (" + ARCHIVED + ", " + MESSAGE_COUNT + ");",
  };

  public static final String ADD_SNIPPET_CONTENT_COLUMN = "ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + SNIPPET_CONTENT + " TEXT DEFAULT NULL;";

  private static final String[] THREAD_PROJECTION = {
      ID, THREAD_CREATION_DATE, MESSAGE_COUNT, ADDRESS, SNIPPET, SNIPPET_CHARSET, READ, UNREAD_COUNT, UNREAD_MENTION_COUNT, DISTRIBUTION_TYPE, ERROR, SNIPPET_TYPE,
      SNIPPET_URI, ARCHIVED, STATUS, DELIVERY_RECEIPT_COUNT, EXPIRES_IN, LAST_SEEN, READ_RECEIPT_COUNT, IS_PINNED, SNIPPET_CONTENT,
  };

  private static final List<String> TYPED_THREAD_PROJECTION = Stream.of(THREAD_PROJECTION)
                                                                    .map(columnName -> TABLE_NAME + "." + columnName)
                                                                    .toList();

  private static final List<String> COMBINED_THREAD_RECIPIENT_GROUP_PROJECTION =
          // wew
          Stream.concat(Stream.concat(Stream.of(TYPED_THREAD_PROJECTION),
                  Stream.of(GroupDatabase.TYPED_GROUP_PROJECTION)),
                  Stream.of(LokiMessageDatabase.groupInviteTable+"."+LokiMessageDatabase.invitingSessionId)
          )
                                                                                       .toList();

  public static String getCreatePinnedCommand() {
    return "ALTER TABLE "+ TABLE_NAME + " " +
            "ADD COLUMN " + IS_PINNED + " INTEGER DEFAULT 0;";
  }

  public static String getUnreadMentionCountCommand() {
    return "ALTER TABLE "+ TABLE_NAME + " " +
            "ADD COLUMN " + UNREAD_MENTION_COUNT + " INTEGER DEFAULT 0;";
  }

  private ConversationThreadUpdateListener updateListener;
  private final MutableSharedFlow<Long> updateNotifications = SharedFlowKt.MutableSharedFlow(0, 256, BufferOverflow.DROP_OLDEST);
  private final Json json;
  private final TextSecurePreferences prefs;

  private final Lazy<@NonNull RecipientRepository> recipientRepository;
  private final Lazy<@NonNull MmsSmsDatabase> mmsSmsDatabase;
  private final Lazy<@NonNull ConfigFactoryProtocol> configFactory;
  private final Lazy<@NonNull MessageNotifier> messageNotifier;
  private final Lazy<@NonNull MmsDatabase> mmsDatabase;
  private final Lazy<@NonNull SmsDatabase> smsDatabase;

  @Inject
  public ThreadDatabase(@dagger.hilt.android.qualifiers.ApplicationContext Context context,
                        Provider<SQLCipherOpenHelper> databaseHelper,
                        Lazy<@NonNull RecipientRepository> recipientRepository,
                        Lazy<@NonNull MmsSmsDatabase> mmsSmsDatabase,
                        Lazy<@NonNull ConfigFactoryProtocol> configFactory,
                        Lazy<@NonNull MessageNotifier> messageNotifier,
                        Lazy<@NonNull MmsDatabase> mmsDatabase,
                        Lazy<@NonNull SmsDatabase> smsDatabase,
                        TextSecurePreferences prefs,
                        Json json) {
    super(context, databaseHelper);
    this.recipientRepository = recipientRepository;
    this.mmsSmsDatabase = mmsSmsDatabase;
    this.configFactory = configFactory;
    this.messageNotifier = messageNotifier;
    this.mmsDatabase = mmsDatabase;
    this.smsDatabase = smsDatabase;

    this.json = json;
    this.prefs = prefs;
  }

  // This method is called when the application is created, providing an opportunity to perform
  // initialization tasks that need to be done only once.
  public void onAppCreated() {
    if (!prefs.getMigratedDisappearingMessagesToMessageContent()) {
       migrateDisappearingMessagesToMessageContent();
       prefs.setMigratedDisappearingMessagesToMessageContent(true);
    }
  }

  @NonNull
  public Flow<Long> getUpdateNotifications() {
    return updateNotifications;
  }

  // As we migrate disappearing messages to MessageContent, we need to ensure that
  // if they appear in the snippet, they have to be re-generated with the new MessageContent.
  private void migrateDisappearingMessagesToMessageContent() {
    String sql = "SELECT " + ID + " FROM " + TABLE_NAME +
            " WHERE " + SNIPPET_TYPE + " & " + MmsSmsColumns.Types.EXPIRATION_TIMER_UPDATE_BIT + " != 0";
    try (final Cursor cursor = getReadableDatabase().rawQuery(sql)) {
       while (cursor.moveToNext()) {
          update(cursor.getLong(0), false);
       }
    }
  }

  public void setUpdateListener(ConversationThreadUpdateListener updateListener) {
    this.updateListener = updateListener;
  }

  private long createThreadForRecipient(Address address) {
    ContentValues contentValues = new ContentValues(2);

    contentValues.put(ADDRESS, address.toString());
    contentValues.put(MESSAGE_COUNT, 0);

    SQLiteDatabase db = getWritableDatabase();
    return db.insert(TABLE_NAME, null, contentValues);
  }

  private void updateThread(long threadId, long count, String body, @Nullable Uri attachment, @Nullable MessageContent messageContent,
                            long date, int status, int deliveryReceiptCount, long type, boolean unarchive,
                            long expiresIn, int readReceiptCount)
  {
    ContentValues contentValues = new ContentValues(7);
    contentValues.put(THREAD_CREATION_DATE, date - date % 1000);
    contentValues.put(MESSAGE_COUNT, count);
    if (!body.isEmpty()) {
      contentValues.put(SNIPPET, body);
    }
    contentValues.put(SNIPPET_CONTENT, messageContent == null ? null : json.encodeToString(MessageContent.Companion.serializer(), messageContent));
    contentValues.put(SNIPPET_URI, attachment == null ? null : attachment.toString());
    contentValues.put(SNIPPET_TYPE, type);
    contentValues.put(STATUS, status);
    contentValues.put(DELIVERY_RECEIPT_COUNT, deliveryReceiptCount);
    contentValues.put(READ_RECEIPT_COUNT, readReceiptCount);
    contentValues.put(EXPIRES_IN, expiresIn);

    if (unarchive) { contentValues.put(ARCHIVED, 0); }

    SQLiteDatabase db = getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID + " = ?", new String[] {threadId + ""});
  }

  public void clearSnippet(long threadId){
    ContentValues contentValues = new ContentValues(1);

    contentValues.put(SNIPPET, "");
    contentValues.put(SNIPPET_CONTENT, "");

    SQLiteDatabase db = getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID + " = ?", new String[] {threadId + ""});
    notifyThreadUpdated(threadId);
  }

  public void deleteThread(long threadId) {
    SQLiteDatabase db = getWritableDatabase();
    if (db.delete(TABLE_NAME, ID_WHERE, new String[] {threadId + ""}) > 0) {
      notifyThreadUpdated(threadId);
    }
  }

  public void trimThreadBefore(long threadId, long timestamp) {
    Log.i("ThreadDatabase", "Trimming thread: " + threadId + " before :"+timestamp);
    smsDatabase.get().deleteMessagesInThreadBeforeDate(threadId, timestamp);
    mmsDatabase.get().deleteMessagesInThreadBeforeDate(threadId, timestamp, false);
    update(threadId, false);
    notifyThreadUpdated(threadId);
  }

  public List<MarkedMessageInfo> setRead(long threadId, long lastReadTime) {

    final List<MarkedMessageInfo> smsRecords = smsDatabase.get().setMessagesRead(threadId, lastReadTime);
    final List<MarkedMessageInfo> mmsRecords = mmsDatabase.get().setMessagesRead(threadId, lastReadTime);

    ContentValues contentValues = new ContentValues(2);
    contentValues.put(READ, smsRecords.isEmpty() && mmsRecords.isEmpty());
    contentValues.put(LAST_SEEN, lastReadTime);

    SQLiteDatabase db = getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {threadId+""});

    notifyThreadUpdated(threadId);

    return CollectionsKt.plus(smsRecords, mmsRecords);
  }

  public List<MarkedMessageInfo> setRead(long threadId, boolean lastSeen) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(READ, 1);
    contentValues.put(UNREAD_COUNT, 0);
    contentValues.put(UNREAD_MENTION_COUNT, 0);

    if (lastSeen) {
      contentValues.put(LAST_SEEN, SnodeAPI.getNowWithOffset());
    }

    SQLiteDatabase db = getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {threadId+""});

    final List<MarkedMessageInfo> smsRecords = smsDatabase.get().setMessagesRead(threadId);
    final List<MarkedMessageInfo> mmsRecords = mmsDatabase.get().setMessagesRead(threadId);

    notifyThreadUpdated(threadId);

    return new LinkedList<MarkedMessageInfo>() {{
      addAll(smsRecords);
      addAll(mmsRecords);
    }};
  }

  public void setCreationDate(long threadId, long date) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(THREAD_CREATION_DATE, date);
    SQLiteDatabase db = getWritableDatabase();
    int updated = db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {threadId+""});
    if (updated > 0) notifyThreadUpdated(threadId);
  }

  public int getDistributionType(long threadId) {
    SQLiteDatabase db     = getReadableDatabase();
    Cursor         cursor = db.query(TABLE_NAME, new String[]{DISTRIBUTION_TYPE}, ID_WHERE, new String[]{String.valueOf(threadId)}, null, null, null);

    try {
      if (cursor != null && cursor.moveToNext()) {
        return cursor.getInt(cursor.getColumnIndexOrThrow(DISTRIBUTION_TYPE));
      }

      return DistributionTypes.DEFAULT;
    } finally {
      if (cursor != null) cursor.close();
    }

  }

  public Cursor searchConversationAddresses(String addressQuery, Set<String> excludeAddresses) {
    if (addressQuery == null || addressQuery.isEmpty()) {
      return null;
    }

    SQLiteDatabase db = getReadableDatabase();
    StringBuilder selection = new StringBuilder(TABLE_NAME + "." + ADDRESS + " LIKE ?");

    List<String> selectionArgs = new ArrayList<>();
    selectionArgs.add(addressQuery + "%");

    // Add exclusion for blocked contacts
    if (excludeAddresses != null && !excludeAddresses.isEmpty()) {
      selection.append(" AND ").append(TABLE_NAME).append(".").append(ADDRESS).append(" NOT IN (");

      // Use the helper method to generate placeholders
      selection.append(generatePlaceholders(excludeAddresses.size()));
      selection.append(")");

      // Add all exclusion addresses to selection args
      selectionArgs.addAll(excludeAddresses);
    }

    String query = createQuery(selection.toString());
    return db.rawQuery(query, selectionArgs.toArray(new String[0]));
  }

  @NonNull
  private ArrayList<ThreadRecord> getThreadRecords(@NonNull final Cursor cursor) {
    final ArrayList<ThreadRecord> threads = new ArrayList<>(cursor.getCount());
    Reader reader = new Reader(cursor);
    ThreadRecord thread;
    while ((thread = reader.getNext()) != null) {
      threads.add(thread);
    }

    return threads;
  }

  @NonNull
  public ArrayList<ThreadRecord> getFilteredConversationList(@Nullable List<Address> filter) {
    if (filter == null || filter.isEmpty())
      return new ArrayList<>(0);

    final String query = createQuery(
            TABLE_NAME + "." + ADDRESS + " IN (SELECT value FROM json_each(?))"
    );

    final String[] selectionArgs = new String[] {
        new JSONArray(CollectionsKt.map(filter, Address::toString)).toString()
    };

    try (final Cursor cursor = getReadableDatabase().rawQuery(query, selectionArgs)) {
      return getThreadRecords(cursor);
    }
  }

  /**
   * @return All blinded conversation addresses in the database
   */
  @NonNull
  public List<Address> getBlindedConversations() {
    final String query = "SELECT " + TABLE_NAME + "." + ADDRESS + " FROM " + TABLE_NAME +
            " WHERE " + TABLE_NAME + "." + ADDRESS + " LIKE '" + GroupUtil.COMMUNITY_INBOX_PREFIX + "%'";

    try(final Cursor cursor = getReadableDatabase().rawQuery(query)) {
      final ArrayList<Address> addresses = new ArrayList<>(cursor.getCount());
      while (cursor.moveToNext()) {
        String address = cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS));
        if (address != null && !address.isEmpty()) {
          addresses.add(Address.fromSerialized(address));
        }
      }

      return addresses;
    }
  }

  /**
   * @return All threads in the database, with their thread ID and Address. Note that
   *   threads don't necessarily mean conversations, as whether you have a conversation
   *   or not depend on the config data. This method returns all threads that exist
   *   in the database, normally this is useful only for data integrity purposes.
   */
  public List<kotlin.Pair<Address, Long>> getAllThreads() {
    return getAllThreads(getReadableDatabase());
  }

  private List<kotlin.Pair<Address, Long>> getAllThreads(SQLiteDatabase db) {
    final String query = "SELECT " + ID + ", " + ADDRESS + " FROM " + TABLE_NAME + " WHERE nullif(" + ADDRESS + ", '') IS NOT NULL";
    try (Cursor cursor = db.rawQuery(query, null)) {
      List<kotlin.Pair<Address, Long>> threads = new ArrayList<>(cursor.getCount());
      while (cursor.moveToNext()) {
        long threadId = cursor.getLong(cursor.getColumnIndexOrThrow(ID));
        String address = cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS));
        if (address != null && !address.isEmpty()) {
          threads.add(new kotlin.Pair<>(Address.fromSerialized(address), threadId));
        }
      }
      return threads;
    }
  }

  /**
   * @param threadId
   * @param timestamp
   * @return true if we have set the last seen for the thread, false if there were no messages in the thread
   */
  public boolean setLastSeen(long threadId, long timestamp) {
    // edge case where we set the last seen time for a conversation before it loads messages (joining community for example)
    Address forThreadId = getRecipientForThreadId(threadId);
    if (mmsSmsDatabase.get().getConversationCount(threadId) <= 0 && forThreadId != null && AddressKt.isCommunity(forThreadId)) return false;

    SQLiteDatabase db = getWritableDatabase();

    ContentValues contentValues = new ContentValues(1);
    long lastSeenTime = timestamp == -1 ? SnodeAPI.getNowWithOffset() : timestamp;
    contentValues.put(LAST_SEEN, lastSeenTime);
    db.beginTransaction();
    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {String.valueOf(threadId)});
    String smsCountSubQuery = "SELECT COUNT(*) FROM "+SmsDatabase.TABLE_NAME+" AS s WHERE t."+ID+" = s."+SmsDatabase.THREAD_ID+" AND s."+SmsDatabase.DATE_SENT+" > t."+LAST_SEEN+" AND s."+SmsDatabase.READ+" = 0";
    String smsMentionCountSubQuery = "SELECT COUNT(*) FROM "+SmsDatabase.TABLE_NAME+" AS s WHERE t."+ID+" = s."+SmsDatabase.THREAD_ID+" AND s."+SmsDatabase.DATE_SENT+" > t."+LAST_SEEN+" AND s."+SmsDatabase.READ+" = 0 AND s."+SmsDatabase.HAS_MENTION+" = 1";
    String smsReactionCountSubQuery = "SELECT COUNT(*) FROM "+SmsDatabase.TABLE_NAME+" AS s WHERE t."+ID+" = s."+SmsDatabase.THREAD_ID+" AND s."+SmsDatabase.DATE_SENT+" > t."+LAST_SEEN+" AND s."+SmsDatabase.REACTIONS_UNREAD+" = 1";
    String mmsCountSubQuery = "SELECT COUNT(*) FROM "+MmsDatabase.TABLE_NAME+" AS m WHERE t."+ID+" = m."+MmsDatabase.THREAD_ID+" AND m."+MmsDatabase.DATE_SENT+" > t."+LAST_SEEN+" AND m."+MmsDatabase.READ+" = 0";
    String mmsMentionCountSubQuery = "SELECT COUNT(*) FROM "+MmsDatabase.TABLE_NAME+" AS m WHERE t."+ID+" = m."+MmsDatabase.THREAD_ID+" AND m."+MmsDatabase.DATE_SENT+" > t."+LAST_SEEN+" AND m."+MmsDatabase.READ+" = 0 AND m."+MmsDatabase.HAS_MENTION+" = 1";
    String mmsReactionCountSubQuery = "SELECT COUNT(*) FROM "+MmsDatabase.TABLE_NAME+" AS m WHERE t."+ID+" = m."+MmsDatabase.THREAD_ID+" AND m."+MmsDatabase.DATE_SENT+" > t."+LAST_SEEN+" AND m."+MmsDatabase.REACTIONS_UNREAD+" = 1";
    String allSmsUnread = "(("+smsCountSubQuery+") + ("+smsReactionCountSubQuery+"))";
    String allMmsUnread = "(("+mmsCountSubQuery+") + ("+mmsReactionCountSubQuery+"))";
    String allUnread = "(("+allSmsUnread+") + ("+allMmsUnread+"))";
    String allUnreadMention = "(("+smsMentionCountSubQuery+") + ("+mmsMentionCountSubQuery+"))";

    String reflectUpdates = "UPDATE "+TABLE_NAME+" AS t SET "+UNREAD_COUNT+" = "+allUnread+", "+UNREAD_MENTION_COUNT+" = "+allUnreadMention+" WHERE "+ID+" = ?";
    db.execSQL(reflectUpdates, new Object[]{threadId});
    db.setTransactionSuccessful();
    db.endTransaction();
    notifyThreadUpdated(threadId);
    return true;
  }


  public Pair<Long, Boolean> getLastSeenAndHasSent(long threadId) {
    SQLiteDatabase db     = getReadableDatabase();
    Cursor         cursor = db.query(TABLE_NAME, new String[]{LAST_SEEN, HAS_SENT}, ID_WHERE, new String[]{String.valueOf(threadId)}, null, null, null);

    try {
      if (cursor != null && cursor.moveToFirst()) {
        return new Pair<>(cursor.getLong(0), cursor.getLong(1) == 1);
      }

      return new Pair<>(-1L, false);
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  public long getLastUpdated(long threadId) {
    SQLiteDatabase db     = getReadableDatabase();
    Cursor         cursor = db.query(TABLE_NAME, new String[]{THREAD_CREATION_DATE}, ID_WHERE, new String[]{String.valueOf(threadId)}, null, null, null);

    try {
      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getLong(0);
      }

      return -1L;
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  public int getMessageCount(long threadId) {
    SQLiteDatabase db      = getReadableDatabase();
    String[]       columns = new String[]{MESSAGE_COUNT};
    String[]       args    = new String[]{String.valueOf(threadId)};
    try (Cursor cursor = db.query(TABLE_NAME, columns, ID_WHERE, args, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getInt(0);
      }

      return 0;
    }
  }

  public List<Long> getThreadIDsFor(List<String> addresses) {
    final String where = ADDRESS + " IN (SELECT value FROM json_each(?))";
    final String whereArg = new JSONArray(addresses).toString();

    try (final Cursor cursor = getReadableDatabase().query(TABLE_NAME, new String[]{ID}, where,
            new String[]{whereArg}, null, null, null)) {
      List<Long> threadIds = new ArrayList<>(cursor.getCount());
      while (cursor.moveToNext()) {
        threadIds.add(cursor.getLong(cursor.getColumnIndexOrThrow(ID)));
      }
      return threadIds;
    }
  }

  public long getThreadIdIfExistsFor(String address) {
    SQLiteDatabase db      = getReadableDatabase();
    String where           = ADDRESS + " = ?";
    String[] recipientsArg = new String[] {address};
    Cursor cursor          = null;

    try {
      cursor = db.query(TABLE_NAME, new String[]{ID}, where, recipientsArg, null, null, null);

      if (cursor != null && cursor.moveToFirst())
        return cursor.getLong(cursor.getColumnIndexOrThrow(ID));
      else
        return -1L;
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public long getThreadIdIfExistsFor(Address address) {
    return getThreadIdIfExistsFor(address.toString());
  }

  public long getOrCreateThreadIdFor(Address address) {
    SQLiteDatabase db            = getReadableDatabase();
    String         where         = ADDRESS + " = ?";
    String[]       recipientsArg = new String[]{address.toString()};
    Cursor         cursor        = null;

    boolean created = false;

    try {
        long threadId;

        // The synchronization here makes sure we don't create two threads for the same recipient at the same time
        synchronized (this) {
            cursor = db.query(TABLE_NAME, new String[]{ID}, where, recipientsArg, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
              threadId = cursor.getLong(cursor.getColumnIndexOrThrow(ID));
            } else {
              threadId = createThreadForRecipient(address);
              created = true;
            }
        }

        if (created) {
          if (updateListener != null) {
              updateListener.threadCreated(address, threadId);
          }

          updateNotifications.tryEmit(threadId);
        }

      return threadId;
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public @Nullable Address getRecipientForThreadId(long threadId) {
    SQLiteDatabase db = getReadableDatabase();

    try(final Cursor cursor = db.query(TABLE_NAME, null, ID + " = ?", new String[] {threadId+""}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        return Address.fromSerialized(cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS)));
      }
    }

    return null;
  }

  public void setHasSent(long threadId, boolean hasSent) {
    ContentValues contentValues = new ContentValues(1);
    final int hasSentValue = hasSent ? 1 : 0;
    contentValues.put(HAS_SENT, hasSentValue);

    if (getWritableDatabase().update(TABLE_NAME, contentValues, ID + " = ? AND " + HAS_SENT + " != ?",
                                                new String[] {String.valueOf(threadId), String.valueOf(hasSentValue)}) > 0) {
      notifyThreadUpdated(threadId);
    }
  }


  public boolean update(long threadId, boolean unarchive) {
    long count                    = mmsSmsDatabase.get().getConversationCount(threadId);

    try (MmsSmsDatabase.Reader reader = mmsSmsDatabase.get().readerFor(mmsSmsDatabase.get().getConversationSnippet(threadId))) {
      MessageRecord record = null;
      if (reader != null) {
        record = reader.getNext();
        while (record != null && record.isDeleted()) {
          record = reader.getNext();
        }
      }
      if (record != null && !record.isDeleted()) {
        updateThread(threadId, count, getFormattedBodyFor(record), getAttachmentUriFor(record), record.getMessageContent(),
                     record.getTimestamp(), record.getDeliveryStatus(), record.getDeliveryReceiptCount(),
                     record.getType(), unarchive, record.getExpiresIn(), record.getReadReceiptCount());
        return false;
      } else {
        // for empty threads or if there is only deleted messages, show an empty snippet
        clearSnippet(threadId);
        return false;
      }
    } finally {
      notifyThreadUpdated(threadId);
    }
  }

  /**
   * @param threadId
   * @param lastSeenTime
   * @return true if we have set the last seen for the thread, false if there were no messages in the thread
   */
  public boolean markAllAsRead(long threadId, long lastSeenTime, boolean force) {
    if (mmsSmsDatabase.get().getConversationCount(threadId) <= 0 && !force) return false;
    List<MarkedMessageInfo> messages = setRead(threadId, lastSeenTime);
    MarkReadReceiver.process(context, messages);
    messageNotifier.get().updateNotification(context, threadId);
    return setLastSeen(threadId, lastSeenTime);
  }

  private @NonNull String getFormattedBodyFor(@NonNull MessageRecord messageRecord) {
    if (messageRecord.isMms()) {
      MmsMessageRecord record = (MmsMessageRecord) messageRecord;
      String attachmentString = record.getSlideDeck().getBody();
      if (!attachmentString.isEmpty()) {
        if (!messageRecord.getBody().isEmpty()) {
          attachmentString = attachmentString + ": " + messageRecord.getBody();
        }
        return attachmentString;
      }
    }
    return messageRecord.getBody();
  }

  private @Nullable Uri getAttachmentUriFor(MessageRecord record) {
    if (!record.isMms() || record.isMmsNotification()) return null;

    SlideDeck slideDeck = ((MediaMmsMessageRecord)record).getSlideDeck();
    Slide     thumbnail = slideDeck.getThumbnailSlide();

    if (thumbnail != null) {
      return thumbnail.getThumbnailUri();
    }

    return null;
  }

  private @NonNull String createQuery(@NonNull String where) {
    String projection = Util.join(COMBINED_THREAD_RECIPIENT_GROUP_PROJECTION, ",");
    return "SELECT " + projection + " FROM " + TABLE_NAME +
            " LEFT OUTER JOIN " + RecipientSettingsDatabase.TABLE_NAME +
            " ON " + TABLE_NAME + "." + ADDRESS + " = " + RecipientSettingsDatabase.TABLE_NAME + "." + RecipientSettingsDatabase.COL_ADDRESS +
            " LEFT OUTER JOIN " + GroupDatabase.TABLE_NAME +
            " ON " + TABLE_NAME + "." + ADDRESS + " = " + GroupDatabase.TABLE_NAME + "." + GROUP_ID +
            " LEFT OUTER JOIN " + LokiMessageDatabase.groupInviteTable +
            " ON "+ TABLE_NAME + "." + ID + " = " + LokiMessageDatabase.groupInviteTable+"."+LokiMessageDatabase.invitingSessionId +
            " WHERE " + where;
  }

  public void notifyThreadUpdated(long threadId) {
    updateNotifications.tryEmit(threadId);
  }

  private class Reader implements Closeable {

    private final Cursor cursor;

    public Reader(Cursor cursor) {
      this.cursor = cursor;
    }

    public int getCount() {
      return cursor == null ? 0 : cursor.getCount();
    }

    public ThreadRecord getNext() {
      if (cursor == null || !cursor.moveToNext())
        return null;

      return getCurrent();
    }

    public ThreadRecord getCurrent() {
      long    threadId         = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.ID));
      Address address          = Address.fromSerialized(cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.ADDRESS)));

      Recipient recipient            = recipientRepository.get().getRecipientSync(address);
      String             body                 = cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET));
      long               date                 = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.THREAD_CREATION_DATE));
      long               count                = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.MESSAGE_COUNT));
      int                unreadCount          = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.UNREAD_COUNT));
      int                unreadMentionCount   = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.UNREAD_MENTION_COUNT));
      long               type                 = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_TYPE));
      int                status               = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.STATUS));
      int                deliveryReceiptCount = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.DELIVERY_RECEIPT_COUNT));
      int                readReceiptCount     = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.READ_RECEIPT_COUNT));
      long               lastSeen             = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.LAST_SEEN));
      String             invitingAdmin       = cursor.getString(cursor.getColumnIndexOrThrow(LokiMessageDatabase.invitingSessionId));
      String messageContentJson = cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_CONTENT));

      if (!TextSecurePreferences.isReadReceiptsEnabled(context)) {
        readReceiptCount = 0;
      }

      MessageRecord lastMessage = null;

      if (count > 0) {
        lastMessage = mmsSmsDatabase.get().getLastMessage(threadId);
      }

      final GroupThreadStatus groupThreadStatus;
      if (recipient.isGroupV2Recipient()) {
        GroupInfo.ClosedGroupInfo group = ConfigFactoryProtocolKt.getGroup(
                configFactory.get(),
                new AccountId(recipient.getAddress().toString())
        );
        if (group != null && group.getDestroyed()) {
          groupThreadStatus = GroupThreadStatus.Destroyed;
        } else if (group != null && group.getKicked()) {
          groupThreadStatus = GroupThreadStatus.Kicked;
        } else {
          groupThreadStatus = GroupThreadStatus.None;
        }
      } else {
        groupThreadStatus = GroupThreadStatus.None;
      }

      MessageContent messageContent;
      try {
          messageContent = (messageContentJson == null || messageContentJson.isEmpty()) ? null : json.decodeFromString(
                  MessageContent.Companion.serializer(),
                  messageContentJson
          );
      } catch (Exception e) {
          Log.e(TAG, "Failed to parse message content for thread: " + threadId, e);
          messageContent = null;
      }

      return new ThreadRecord(body, lastMessage, recipient, date, count,
                              unreadCount, unreadMentionCount, threadId, deliveryReceiptCount, status, type,
              lastSeen, readReceiptCount, invitingAdmin, groupThreadStatus, messageContent);
    }

    @Override
    public void close() {
      if (cursor != null) {
        cursor.close();
      }
    }
  }
}
