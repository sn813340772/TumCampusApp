package de.tum.in.tumcampus.models.managers;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import de.tum.in.tumcampus.auxiliary.Const;
import de.tum.in.tumcampus.auxiliary.Utils;
import de.tum.in.tumcampus.cards.Card;
import de.tum.in.tumcampus.cards.ChatMessagesCard;
import de.tum.in.tumcampus.models.ChatClient;
import de.tum.in.tumcampus.models.ChatMember;
import de.tum.in.tumcampus.models.ChatRoom;
import de.tum.in.tumcampus.models.LecturesSearchRow;
import retrofit.RetrofitError;

/**
 * TUMOnline cache manager, allows caching of TUMOnline requests
 */
public class ChatRoomManager implements Card.ProvidesCard {

    public static final int COL_GROUP_ID = 0;
    public static final int COL_NAME = 1;
    public static final int COL_SEMESTER = 2;
    public static final int COL_SEMESTER_ID = 3;
    public static final int COL_JOINED = 4;
    public static final int COL_LV_NR = 5;
    public static final int COL_CONTRIBUTOR = 6;

    /**
     * Database connection
     */
    private final SQLiteDatabase db;

    /**
     * Constructor, open/create database, create table if necessary
     *
     * @param context Context
     */
    public ChatRoomManager(Context context) {
        db = DatabaseManager.getDb(context);

        // create table if needed
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_room (group_id INTEGER, name VARCHAR, " +
                "semester VARCHAR, semester_id VARCHAR, joined INTEGER, _id INTEGER, contributor VARCHAR, PRIMARY KEY(name, semester_id))");
    }

    /**
     * Gets all chat rooms that you have joined(1)/not joined(0) for the specified room
     *
     * @param joined 1=joined, 0=not joined/left chat room, -1=not joined
     * @return List of chat messages
     */
    public Cursor getAllByStatus(int joined) {
        if (joined == 0) {
            return db.rawQuery("SELECT * FROM chat_room WHERE joined=0 OR joined=-1 ORDER BY semester_id DESC, name", null);
        } else {
            return db.rawQuery("SELECT * FROM chat_room WHERE joined=1 ORDER BY semester_id DESC, name", null);
        }
    }

    /**
     * Saves the given message into database
     */
    public void replaceInto(LecturesSearchRow lecture) {
        Utils.logv("replace " + lecture.getTitel());

        Cursor cur = db.rawQuery("SELECT _id FROM chat_room WHERE name=? AND semester_id=?", new String[]{lecture.getTitel(), lecture.getSemester_id()});
        cur.moveToFirst();
        if (cur.getCount() >= 1) {
            db.execSQL("UPDATE chat_room SET semester=?, _id=?, contributor=?",
                    new String[]{lecture.getSemester_name(), lecture.getStp_lv_nr(), lecture.getVortragende_mitwirkende()});
        } else {
            db.execSQL("REPLACE INTO chat_room (group_id,name,semester_id,semester,joined,_id,contributor) " +
                            "VALUES (-1,?,?,?,-1,?,?)",
                    new String[]{lecture.getTitel(), lecture.getSemester_id(),
                            lecture.getSemester_name(), lecture.getStp_lv_nr(), lecture.getVortragende_mitwirkende()});
        }
    }

    /**
     * Saves the given lectures into database
     */
    public void replaceInto(List<LecturesSearchRow> lectures) {
        db.beginTransaction();
        Cursor cur = db.rawQuery("SELECT _id FROM chat_room", null);
        HashSet<String> set = new HashSet<String>();
        if (cur.moveToFirst()) {
            do {
                set.add(cur.getString(0));
            } while (cur.moveToNext());
        }
        cur.close();

        for (LecturesSearchRow lecture : lectures) {
            if (!set.contains(lecture.getStp_lv_nr())) {
                replaceInto(lecture);
            }
        }
        db.setTransactionSuccessful();
        db.endTransaction();
    }

    /**
     * Saves the given chat rooms into database
     */
    public void replaceIntoRooms(List<ChatRoom> rooms) {
        db.beginTransaction();
        db.execSQL("UPDATE chat_room SET joined=0 WHERE joined=1");
        for (ChatRoom room : rooms) {
            String roomName = room.getName();
            String semester = "ZZZ";
            if (roomName.contains(":")) {
                semester = roomName.substring(0, 3);
                roomName = roomName.substring(4);
            }

            Cursor cur = db.rawQuery("SELECT _id FROM chat_room WHERE name=? AND semester_id=?", new String[]{roomName, semester});
            cur.moveToFirst();
            if (cur.getCount() >= 1) {
                db.execSQL("UPDATE chat_room SET group_id=?, joined=1 WHERE name=? AND semester_id=?",
                        new String[]{"" + room.getId(), roomName, semester});
            } else {
                db.execSQL("REPLACE INTO chat_room (group_id,name,semester_id,semester,joined,_id,contributor) " +
                        "VALUES (?,?,?,'',1,0,'')", new String[]{"" + room.getId(), roomName, semester});
            }
        }
        db.setTransactionSuccessful();
        db.endTransaction();
    }

    public void join(ChatRoom currentChatRoom) {
        db.execSQL("UPDATE chat_room SET group_id=?, joined=1 WHERE name=? AND semester_id=?",
                new String[]{"" + currentChatRoom.getId(), currentChatRoom.getName().substring(4), currentChatRoom.getName().substring(0, 3)});
    }

    public void leave(ChatRoom currentChatRoom) {
        db.execSQL("UPDATE chat_room SET group_id=?, joined=0 WHERE name=? AND semester_id=?",
                new String[]{"" + currentChatRoom.getId(), currentChatRoom.getName().substring(4), currentChatRoom.getName().substring(0, 3)});
    }


    @Override
    public void onRequestCard(Context context) {
        ChatRoomManager manager = new ChatRoomManager(context);

        // Use this to make sure chat_message table exists
        new ChatMessageManager(context, 0);

        ChatMember member;
        try {
            // Join all new chat rooms
            if(Utils.getSettingBool(context, Const.AUTO_JOIN_NEW_ROOMS, false)) {
                ArrayList<String> newRooms = manager.getNewUnjoined();

                for (String roomId : newRooms) {
                    try {
                        ChatRoom currentChatRoom = new ChatRoom(roomId);
                        currentChatRoom = ChatClient.getInstance(context).createGroup(currentChatRoom);
                        manager.join(currentChatRoom);
                    } catch (RetrofitError e) {
                        Utils.log(e);
                    }
                }
            }

            // Get member instance
            String lrzId = Utils.getSetting(context, Const.LRZ_ID, "");
            member = ChatClient.getInstance(context).getMember(lrzId);

            // Catch a possible error, when we didn't get something returned
            if (member == null || member.getLrzId() == null) {
                return;
            }
        } catch(RetrofitError e) {
            Utils.log(e);
            return;
        }

        // Get all rooms that have unread messages
        Cursor cur = manager.getUnreadRooms();
        if (cur.moveToFirst()) {
            do {
                ChatMessagesCard card = new ChatMessagesCard(context);
                card.setChatRoom(cur.getString(0), cur.getInt(1), cur.getString(2)+":"+cur.getString(0), member);
                card.apply();
            } while (cur.moveToNext());
        }
        cur.close();
    }

    private ArrayList<String> getNewUnjoined() {
        Cursor cursor = db.rawQuery("SELECT r.semester_id, r.name " +
                "FROM chat_room r, (SELECT semester_id FROM chat_room " +
                "WHERE (NOT semester_id IS NULL) AND semester_id!='' " +
                "ORDER BY semester_id DESC LIMIT 1) AS new " +
                "WHERE r.semester_id=new.semester_id AND r.joined=-1", null);
        ArrayList<String> list = new ArrayList<String>();
        if (cursor.moveToFirst()) {
            do {
                list.add(cursor.getString(0) + ":" + cursor.getString(1));
            } while (cursor.moveToNext());
        }
        cursor.close();
        return list;
    }

    private Cursor getUnreadRooms() {
        return db.rawQuery("SELECT r.name,r.group_id,r.semester_id " +
                "FROM chat_room r, (SELECT room FROM chat_message " +
                "WHERE read=0 GROUP BY room) AS c " +
                "WHERE r.group_id=c.room " +
                "ORDER BY r.semester_id DESC, r.name", null);
    }
}