package com.m3.veil.ui.chat.sheets;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.ui.chat.ChatActivity;
import com.m3.veil.webrtc.NearbyMeshController;

import java.util.List;

/**
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * NEARBY RADAR BOTTOM SHEET (#15, #17, #25)
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * Interactive proximity discovery sheet:
 * - Live radar scanning status using Wi-Fi Direct & Bluetooth LE.
 * - Discovered peers with RSSI distance estimation and Cryptographic PSI Interest Tag matches (#17).
 * - "Join Ephemeral Proximity Group (#25)" quick action.
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 */
public class NearbyRadarBottomSheet extends BottomSheetDialogFragment implements NearbyMeshController.NearbyMeshObserver {

    private NearbyMeshController mMeshController;
    private LinearLayout mPeersContainer;
    private TextView mTvStatus;
    private TextView mTvPeerCount;
    private ProgressBar mScanningBar;

    public static NearbyRadarBottomSheet newInstance() {
        return new NearbyRadarBottomSheet();
    }

    @Override
    public int getTheme() {
        return R.style.ModernBottomSheetDialogTheme;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Context context = requireContext();
        mMeshController = NearbyMeshController.getInstance(context);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int padH = (int) (20 * getResources().getDisplayMetrics().density);
        int padV = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(padH, padV, padH, padV * 2);

        // Drag handle
        View handle = new View(context);
        int handleW = (int) (36 * getResources().getDisplayMetrics().density);
        int handleH = (int) (4 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (14 * getResources().getDisplayMetrics().density);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
        root.addView(handle);

        // Header Title
        TextView tvTitle = new TextView(context);
        tvTitle.setText("📡 Local Proximity Radar (#15, #17, #25)");
        tvTitle.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
        tvTitle.setTextSize(18.5f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        root.addView(tvTitle);

        mTvStatus = new TextView(context);
        mTvStatus.setText("Scanning via Wi-Fi Direct & BLE with Blind Geohash tokens...");
        mTvStatus.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));
        mTvStatus.setTextSize(13);
        mTvStatus.setPadding(0, 4, 0, (int) (10 * getResources().getDisplayMetrics().density));
        root.addView(mTvStatus);

        mScanningBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        mScanningBar.setIndeterminate(true);
        mScanningBar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (4 * getResources().getDisplayMetrics().density)));
        root.addView(mScanningBar);

        // Quick action: Join Ephemeral Proximity Mesh Room (#25)
        MaterialButton btnJoinRoom = new MaterialButton(context);
        btnJoinRoom.setText("🚀 Enter Local Ephemeral Room (< 2 km)");
        btnJoinRoom.setCornerRadius((int) (20 * getResources().getDisplayMetrics().density));
        btnJoinRoom.setBackgroundColor(ContextCompat.getColor(context, R.color.colorPrimary));
        btnJoinRoom.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = (int) (12 * getResources().getDisplayMetrics().density);
        btnLp.bottomMargin = (int) (12 * getResources().getDisplayMetrics().density);
        btnJoinRoom.setLayoutParams(btnLp);
        btnJoinRoom.setOnClickListener(v -> {
            dismiss();
            mMeshController.ensureNearbyRoomExists(EncryptedChatDatabase.getInstance(context));
            Intent intent = new Intent(context, ChatActivity.class);
            intent.putExtra("chatId", NearbyMeshController.NEARBY_CHAT_ID);
            intent.putExtra("recipientName", NearbyMeshController.NEARBY_CHAT_NAME);
            startActivity(intent);
        });
        root.addView(btnJoinRoom);

        mTvPeerCount = new TextView(context);
        mTvPeerCount.setText("Discovered Hardware Peers (0)");
        mTvPeerCount.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
        mTvPeerCount.setTextSize(14.5f);
        mTvPeerCount.setTypeface(null, Typeface.BOLD);
        mTvPeerCount.setPadding(0, 0, 0, (int) (8 * getResources().getDisplayMetrics().density));
        root.addView(mTvPeerCount);

        ScrollView scroll = new ScrollView(context);
        mPeersContainer = new LinearLayout(context);
        mPeersContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mPeersContainer);
        root.addView(scroll);

        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mMeshController != null) {
            mMeshController.addObserver(this);
            mMeshController.startProximityMesh();
            renderPeers(mMeshController.getDiscoveredPeers());
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mMeshController != null) {
            mMeshController.removeObserver(this);
        }
    }

    private void renderPeers(List<NearbyMeshController.NearbyPeer> peers) {
        if (mPeersContainer == null || getContext() == null) return;
        mPeersContainer.removeAllViews();
        mTvPeerCount.setText("Discovered Hardware Peers (" + peers.size() + ")");

        if (peers.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText("Searching for nearby VEIL peers within Bluetooth & Wi-Fi Direct range...\nNo internet required.");
            empty.setTextColor(ContextCompat.getColor(requireContext(), R.color.textSecondary));
            empty.setTextSize(13);
            empty.setPadding(0, (int) (14 * getResources().getDisplayMetrics().density), 0, 0);
            mPeersContainer.addView(empty);
            return;
        }

        for (NearbyMeshController.NearbyPeer peer : peers) {
            MaterialCardView card = new MaterialCardView(requireContext());
            card.setRadius(24f);
            card.setCardElevation(0f);
            card.setStrokeWidth(2);
            card.setStrokeColor(peer.commonInterestsCount > 0 ? ContextCompat.getColor(requireContext(), R.color.colorPrimary) : Color.parseColor("#263238"));
            card.setCardBackgroundColor(Color.parseColor("#1A202C"));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = (int) (10 * getResources().getDisplayMetrics().density);
            card.setLayoutParams(cardLp);

            LinearLayout item = new LinearLayout(requireContext());
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding((int) (14 * getResources().getDisplayMetrics().density), (int) (12 * getResources().getDisplayMetrics().density), (int) (14 * getResources().getDisplayMetrics().density), (int) (12 * getResources().getDisplayMetrics().density));

            // Name + distance badge
            LinearLayout header = new LinearLayout(requireContext());
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            TextView tvName = new TextView(requireContext());
            tvName.setText("🎭 " + peer.alias);
            tvName.setTextColor(Color.WHITE);
            tvName.setTextSize(15.5f);
            tvName.setTypeface(null, Typeface.BOLD);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tvName.setLayoutParams(nameLp);
            header.addView(tvName);

            TextView tvDist = new TextView(requireContext());
            tvDist.setText("📍 " + peer.getFormattedDistance());
            tvDist.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary));
            tvDist.setTextSize(13);
            tvDist.setTypeface(null, Typeface.BOLD);
            header.addView(tvDist);

            item.addView(header);

            // Channel tag (BLE vs Wi-Fi Direct)
            String transport = peer.isWifiDirectCapable ? "Wi-Fi Direct P2P" : "Bluetooth Low Energy";
            TextView tvTransport = new TextView(requireContext());
            tvTransport.setText("Signal: " + peer.rssi + " dBm • Transport: " + transport);
            tvTransport.setTextColor(ContextCompat.getColor(requireContext(), R.color.textSecondary));
            tvTransport.setTextSize(12);
            tvTransport.setPadding(0, 2, 0, 4);
            item.addView(tvTransport);

            // Private Set Intersection tags (#17)
            if (peer.commonInterestsCount > 0) {
                TextView tvPsi = new TextView(requireContext());
                tvPsi.setText("✨ " + peer.commonInterestsCount + " Shared Interests: " + android.text.TextUtils.join(", ", peer.commonTags));
                tvPsi.setTextColor(Color.parseColor("#FFD700"));
                tvPsi.setTextSize(12.5f);
                item.addView(tvPsi);
            }

            card.addView(item);
            card.setOnClickListener(v -> {
                dismiss();
                Toast.makeText(requireContext(), "Opening P2P channel with " + peer.alias, Toast.LENGTH_SHORT).show();
                Intent chatIntent = new Intent(requireContext(), ChatActivity.class);
                chatIntent.putExtra("recipientId", peer.id);
                chatIntent.putExtra("recipientName", peer.alias);
                startActivity(chatIntent);
            });

            mPeersContainer.addView(card);
        }
    }

    @Override
    public void onPeerDiscovered(NearbyMeshController.NearbyPeer peer) {
        if (mMeshController != null) {
            renderPeers(mMeshController.getDiscoveredPeers());
        }
    }

    @Override
    public void onPeerUpdated(NearbyMeshController.NearbyPeer peer) {
        if (mMeshController != null) {
            renderPeers(mMeshController.getDiscoveredPeers());
        }
    }

    @Override
    public void onPeerLost(String peerId) {
        if (mMeshController != null) {
            renderPeers(mMeshController.getDiscoveredPeers());
        }
    }

    @Override
    public void onLocalMeshRoomConnected(int peerCount) {
        if (mTvStatus != null) {
            mTvStatus.setText("Connected to off-grid mesh: " + peerCount + " active nodes");
        }
    }

    @Override
    public void onLocalMeshMessageReceived(String senderAlias, String text, long timestamp) {
        // Handled in ChatActivity
    }
}
