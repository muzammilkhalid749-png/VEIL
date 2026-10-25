const functions = require("firebase-functions");
const admin = require("firebase-admin");
admin.initializeApp();

exports.sendChatNotification = functions.firestore
    .document("chats/{chatId}/messages/{messageId}")
    .onCreate(async (snap, context) => {
        const messageData = snap.data();
        const senderId = messageData.senderId;
        const receiverId = messageData.receiverId;
        const chatId = context.params.chatId;

        // Don't send notification to the person who sent the message
        if (!receiverId) return null;

        try {
            // 1. Get the receiver's FCM token and User info
            const receiverDoc = await admin.firestore().collection("users").doc(receiverId).get();
            const senderDoc = await admin.firestore().collection("users").doc(senderId).get();

            if (!receiverDoc.exists || !senderDoc.exists) return null;

            const receiverToken = receiverDoc.data().fcmToken;
            const senderName = senderDoc.data().username || "Someone";

            if (!receiverToken) {
                console.log("No FCM token for user, cannot send notification.");
                return null;
            }

            // 2. Build the notification message
            const message = {
                token: receiverToken,
                notification: {
                    title: senderName,
                    body: "Sent you a new message"
                },
                data: {
                    chatId: String(chatId),
                    messageId: String(context.params.messageId),
                    senderId: String(senderId),
                    type: "chat_message"
                },
                android: {
                    priority: "high"
                }
            };

            // 3. Send the notification via FCM
            const response = await admin.messaging().send(message);
            console.log("Successfully sent message:", response);
            
            // 4. Update message delivered status to true since it was sent to FCM
            await snap.ref.update({ delivered: true });

            return response;
        } catch (error) {
            console.error("Error sending notification:", error);
            return null;
        }
    });

/**
 * Call wake-ups.
 *
 * A caller writes an INVITE into users/{callee}/call_signals. Without a push the
 * callee only discovers it the next time its Firestore listener attaches, which
 * is why a killed app used to "receive" the call minutes later. This trigger
 * sends a high-priority, short-TTL data push so the callee's device rings
 * immediately, and a cancellation push so a dead ring is torn down.
 *
 * Data-only payloads are used deliberately: the Android client owns the
 * notification (full-screen incoming-call UI), and no call metadata leaks into
 * a notification service.
 */
exports.sendCallNotification = functions.firestore
    .document("users/{userId}/call_signals/{signalId}")
    .onCreate(async (snap, context) => {
        const data = snap.data() || {};
        const type = data.type;
        const userId = context.params.userId;
        const from = data.from;

        // Never ring the sender's own device, and only act on lifecycle signals.
        if (from && from === userId) return null;
        if (!["invite", "video_upgrade_request", "end", "decline"].includes(type)) return null;

        try {
            const userDoc = await admin.firestore().collection("users").doc(userId).get();
            if (!userDoc.exists) return null;
            const token = userDoc.data().fcmToken;
            if (!token) {
                console.log("No FCM token for callee, cannot wake.", userId);
                return null;
            }

            let callId = data.callId || "";
            let callType = "audio";
            try {
                if (typeof data.signal === "string") {
                    const parsed = JSON.parse(data.signal);
                    callId = parsed.callId || callId;
                    callType = parsed.callType || callType;
                }
            } catch (e) {
                // malformed envelope; fall back to the indexed fields
            }

            let senderName = "Incoming Call";
            if (from) {
                try {
                    const senderDoc = await admin.firestore().collection("users").doc(from).get();
                    if (senderDoc.exists) {
                        senderName = senderDoc.data().displayName || senderDoc.data().username || "Incoming Call";
                    }
                } catch (ignored) {}
            }

            const isEnd = type === "end" || type === "decline";
            const isUpgrade = type === "video_upgrade_request";
            const message = {
                token: token,
                data: {
                    type: isEnd ? "call_end" : (isUpgrade ? "call_upgrade" : "call_wake"),
                    callId: String(callId),
                    senderId: String(from || ""),
                    senderName: String(senderName),
                    callType: String(callType)
                },
                android: {
                    priority: "high",
                    ttl: (isEnd ? 60 : 35) * 1000 // Milliseconds
                }
            };

            const response = await admin.messaging().send(message);
            console.log("Call push sent (" + message.data.type + ") to", userId, response);
            return response;
        } catch (error) {
            console.error("Error sending call notification:", error);
            return null;
        }
    });

/**
 * Zero-Knowledge P2P Mesh Wake-Knock push trigger.
 * Watches users/{userId}/mesh_signals/{signalId}.
 * Wakes the sleeping device within 500ms to open WebRTC DataChannels and process pending mesh outbox items.
 * Immediately purges the mesh_signals doc after delivery to leave zero digital footprint in the cloud.
 */
exports.sendMeshWakeKnock = functions.firestore
    .document("users/{userId}/mesh_signals/{signalId}")
    .onCreate(async (snap, context) => {
        const data = snap.data() || {};
        const userId = context.params.userId;
        const mailbox = data.mailbox || "";

        try {
            const userDoc = await admin.firestore().collection("users").doc(userId).get();
            if (!userDoc.exists) {
                await snap.ref.delete();
                return null;
            }
            const token = userDoc.data().fcmToken;
            if (!token) {
                console.log("No FCM token for user, cannot wake mesh daemon:", userId);
                await snap.ref.delete();
                return null;
            }

            const message = {
                token: token,
                data: {
                    type: "mesh_wake",
                    mailbox: String(mailbox),
                    signalId: String(context.params.signalId)
                },
                android: {
                    priority: "high",
                    ttl: 60 * 1000 // 60s TTL
                }
            };

            const response = await admin.messaging().send(message);
            console.log("Mesh wake-knock push sent to", userId, response);

            // Immediate Zero-Knowledge purge of signal document
            await snap.ref.delete();
            return response;
        } catch (error) {
            console.error("Error sending mesh wake-knock:", error);
            try {
                await snap.ref.delete();
            } catch (ignored) {}
            return null;
        }
    });
