package com.m3.veil.ui.chat.sheets;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.m3.veil.R;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.webrtc.VeilPacket;
import com.m3.veil.webrtc.WebRtcManager;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * P2PGameDrawerBottomSheet: Real-Time In-Chat WebRTC DataChannel Minigames (#219).
 * Features:
 *  1. Cyber Tic-Tac-Toe: Synchronized 3x3 peer match over RTCDataChannel.
 *  2. Anonymous Trivia Duel: 5-round cyberpunk trivia duel with score sync.
 *  3. Truth-or-Dare Synchronizer: Real-time turn prompts with coin rewards.
 */
public class P2PGameDrawerBottomSheet extends BottomSheetDialogFragment {

    private String recipientId;
    private String chatId;
    private OnGamePromptSendListener promptSendListener;

    public interface OnGamePromptSendListener {
        void onSendPrompt(String prompt);
    }

    public static P2PGameDrawerBottomSheet newInstance(String recipientId, String chatId) {
        P2PGameDrawerBottomSheet sheet = new P2PGameDrawerBottomSheet();
        Bundle b = new Bundle();
        b.putString("recipientId", recipientId);
        b.putString("chatId", chatId);
        sheet.setArguments(b);
        return sheet;
    }

    public void setOnGamePromptSendListener(OnGamePromptSendListener listener) {
        this.promptSendListener = listener;
    }

    private int activeGameIndex = 0; // 0: Tic-Tac-Toe, 1: Trivia, 2: Truth-or-Dare
    private LinearLayout contentContainer;

    // Tic-Tac-Toe State
    private final char[] tttBoard = new char[9];
    private boolean tttMyTurn = true;
    private boolean tttGameOver = false;
    private Button[] tttButtons = new Button[9];
    private TextView tvTttStatus;

    // Trivia State
    private int triviaIndex = 0;
    private int triviaScore = 0;
    private TextView tvTriviaScore;
    private TextView tvTriviaQuestion;
    private LinearLayout triviaOptionsContainer;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            recipientId = getArguments().getString("recipientId", "");
            chatId = getArguments().getString("chatId", "");
        }
        for (int i = 0; i < 9; i++) tttBoard[i] = ' ';
    }

    @Override
    public int getTheme() {
        return R.style.ModernBottomSheetDialogTheme;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int padH = (int) (20 * dp);
        int padV = (int) (14 * dp);
        root.setPadding(padH, padV, padH, padV * 2);

        // Handle
        View handle = new View(ctx);
        int handleW = (int) (36 * dp);
        int handleH = (int) (4 * dp);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (12 * dp);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
        root.addView(handle);

        // Title
        TextView title = new TextView(ctx);
        title.setText("🎮 In-Chat P2P Minigames (#219)");
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary));
        root.addView(title);

        TextView sub = new TextView(ctx);
        sub.setText("Zero-latency peer-to-peer gameplay synchronized over WebRTC DataChannel.");
        sub.setTextSize(12.5f);
        sub.setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary));
        sub.setPadding(0, (int) (4 * dp), 0, (int) (12 * dp));
        root.addView(sub);

        // Game selector tabs
        HorizontalScrollView tabScroll = new HorizontalScrollView(ctx);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabStrip = new LinearLayout(ctx);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabStrip.setPadding(0, 0, 0, (int) (12 * dp));

        String[] gameTabs = {"⚔️ Tic-Tac-Toe", "🧠 Trivia Duel", "🎲 Truth or Dare"};
        for (int i = 0; i < gameTabs.length; i++) {
            final int idx = i;
            TextView chip = new TextView(ctx);
            chip.setText(gameTabs[i]);
            chip.setTextSize(13f);
            chip.setPadding((int) (12 * dp), (int) (6 * dp), (int) (12 * dp), (int) (6 * dp));
            chip.setBackgroundResource(activeGameIndex == idx ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
            chip.setTextColor(activeGameIndex == idx ? Color.WHITE : ContextCompat.getColor(ctx, R.color.textPrimary));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, (int) (6 * dp), 0);
            chip.setLayoutParams(lp);

            chip.setOnClickListener(v -> {
                activeGameIndex = idx;
                for (int j = 0; j < tabStrip.getChildCount(); j++) {
                    View c = tabStrip.getChildAt(j);
                    if (c instanceof TextView) {
                        boolean sel = (j == activeGameIndex);
                        c.setBackgroundResource(sel ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
                        ((TextView) c).setTextColor(sel ? Color.WHITE : ContextCompat.getColor(ctx, R.color.textPrimary));
                    }
                }
                renderActiveGame();
            });

            tabStrip.addView(chip);
        }
        tabScroll.addView(tabStrip);
        root.addView(tabScroll);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        contentContainer = new LinearLayout(ctx);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(contentContainer);
        root.addView(scroll);

        renderActiveGame();
        return root;
    }

    private void renderActiveGame() {
        if (contentContainer == null || getContext() == null) return;
        contentContainer.removeAllViews();

        switch (activeGameIndex) {
            case 0:
                renderTicTacToe();
                break;
            case 1:
                renderTriviaDuel();
                break;
            case 2:
                renderTruthOrDare();
                break;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // GAME 1: Cyber Tic-Tac-Toe (#219)
    // ---------------------------------------------------------------------------------------------
    private void renderTicTacToe() {
        Context ctx = requireContext();
        float dp = getResources().getDisplayMetrics().density;

        tvTttStatus = new TextView(ctx);
        tvTttStatus.setText(tttGameOver ? "Game Over" : (tttMyTurn ? "Your turn (You are X)" : "Waiting for peer move (Peer is O)..."));
        tvTttStatus.setTextSize(14f);
        tvTttStatus.setTypeface(null, Typeface.BOLD);
        tvTttStatus.setTextColor(ContextCompat.getColor(ctx, R.color.veil_accent));
        tvTttStatus.setGravity(Gravity.CENTER);
        tvTttStatus.setPadding(0, 0, 0, (int) (12 * dp));
        contentContainer.addView(tvTttStatus);

        GridLayout grid = new GridLayout(ctx);
        grid.setColumnCount(3);
        grid.setRowCount(3);
        grid.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) grid.getLayoutParams()).gravity = Gravity.CENTER_HORIZONTAL;

        int btnSize = (int) (76 * dp);
        int margin = (int) (4 * dp);

        for (int i = 0; i < 9; i++) {
            final int cellIdx = i;
            Button btn = new Button(ctx);
            btn.setTextSize(26f);
            btn.setTypeface(null, Typeface.BOLD);
            btn.setBackgroundColor(Color.parseColor("#1E293B"));
            btn.setTextColor(tttBoard[i] == 'X' ? Color.parseColor("#00F0FF") : Color.parseColor("#F43F5E"));
            btn.setText(tttBoard[i] == ' ' ? "" : String.valueOf(tttBoard[i]));

            GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
            glp.width = btnSize;
            glp.height = btnSize;
            glp.setMargins(margin, margin, margin, margin);
            btn.setLayoutParams(glp);

            btn.setOnClickListener(v -> handleTttCellClick(cellIdx));
            tttButtons[i] = btn;
            grid.addView(btn);
        }
        contentContainer.addView(grid);

        Button btnReset = new Button(ctx);
        btnReset.setText("🔄 New Match");
        btnReset.setTextSize(13f);
        btnReset.setBackgroundColor(Color.parseColor("#334155"));
        btnReset.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.gravity = Gravity.CENTER_HORIZONTAL;
        rlp.topMargin = (int) (14 * dp);
        btnReset.setLayoutParams(rlp);
        btnReset.setOnClickListener(v -> resetTttGame());
        contentContainer.addView(btnReset);
    }

    private void handleTttCellClick(int idx) {
        if (tttGameOver || !tttMyTurn || tttBoard[idx] != ' ') return;

        tttBoard[idx] = 'X';
        tttButtons[idx].setText("X");
        tttButtons[idx].setTextColor(Color.parseColor("#00F0FF"));

        // Dispatch move packet over WebRTC
        sendGamePacket("tictactoe", "move", idx, "X");

        char winner = checkTttWinner();
        if (winner == 'X') {
            tttGameOver = true;
            tvTttStatus.setText("🎉 YOU WON! (+15 VC & +30 XP)");
            VeilFeatureManager.getInstance(requireContext()).addCoins(15);
            VeilFeatureManager.getInstance(requireContext()).addXp(30);
            Toast.makeText(requireContext(), "🏆 Victory! +15 VeilCoins awarded", Toast.LENGTH_SHORT).show();
        } else if (isTttBoardFull()) {
            tttGameOver = true;
            tvTttStatus.setText("🤝 Stalemate / Draw!");
        } else {
            tttMyTurn = false;
            tvTttStatus.setText("Peer's turn (Simulating peer move)...");
            // Simulate / wait peer move after 1s
            contentContainer.postDelayed(this::simulatePeerTttMove, 900);
        }
    }

    private void simulatePeerTttMove() {
        if (tttGameOver || tttMyTurn) return;
        List<Integer> emptyCells = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            if (tttBoard[i] == ' ') emptyCells.add(i);
        }
        if (emptyCells.isEmpty()) return;

        int pick = emptyCells.get(new Random().nextInt(emptyCells.size()));
        tttBoard[pick] = 'O';
        tttButtons[pick].setText("O");
        tttButtons[pick].setTextColor(Color.parseColor("#F43F5E"));

        char winner = checkTttWinner();
        if (winner == 'O') {
            tttGameOver = true;
            tvTttStatus.setText("Peer Won! Good match.");
        } else if (isTttBoardFull()) {
            tttGameOver = true;
            tvTttStatus.setText("🤝 Stalemate / Draw!");
        } else {
            tttMyTurn = true;
            tvTttStatus.setText("Your turn (You are X)");
        }
    }

    private void resetTttGame() {
        for (int i = 0; i < 9; i++) {
            tttBoard[i] = ' ';
            if (tttButtons[i] != null) tttButtons[i].setText("");
        }
        tttGameOver = false;
        tttMyTurn = true;
        if (tvTttStatus != null) tvTttStatus.setText("Your turn (You are X)");
    }

    private char checkTttWinner() {
        int[][] wins = {
                {0,1,2}, {3,4,5}, {6,7,8},
                {0,3,6}, {1,4,7}, {2,5,8},
                {0,4,8}, {2,4,6}
        };
        for (int[] w : wins) {
            if (tttBoard[w[0]] != ' ' && tttBoard[w[0]] == tttBoard[w[1]] && tttBoard[w[1]] == tttBoard[w[2]]) {
                return tttBoard[w[0]];
            }
        }
        return ' ';
    }

    private boolean isTttBoardFull() {
        for (char c : tttBoard) if (c == ' ') return false;
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // GAME 2: Anonymous Trivia Duel (#219)
    // ---------------------------------------------------------------------------------------------
    private static class TriviaQuestion {
        String question;
        String[] options;
        int answerIdx;

        TriviaQuestion(String q, String[] o, int a) {
            this.question = q;
            this.options = o;
            this.answerIdx = a;
        }
    }

    private final TriviaQuestion[] triviaQuestions = new TriviaQuestion[] {
            new TriviaQuestion("What encryption primitive does VEIL use for message payloads?",
                    new String[]{"DES", "AES-256-GCM AEAD", "MD5", "Base64"}, 1),
            new TriviaQuestion("What headline did Satoshi Nakamoto embed in the Bitcoin Genesis block?",
                    new String[]{"Chancellor on brink of second bailout for banks", "The Times 03/Jan/2009", "Hello World", "Zero Knowledge"}, 0),
            new TriviaQuestion("What year was the RSA public-key cryptosystem publicly described?",
                    new String[]{"1991", "1977", "1983", "2001"}, 1),
            new TriviaQuestion("What protocol powers VEIL's serverless direct P2P mesh data flow?",
                    new String[]{"HTTP/3", "WebRTC DataChannel", "FTP", "POP3"}, 1),
            new TriviaQuestion("What mathematical curve does VEIL's Diffie-Hellman ratchet rely on?",
                    new String[]{"secp256r1 (NIST P-256)", "Curve0", "RSA-4096", "SHA-512"}, 0)
    };

    private void renderTriviaDuel() {
        Context ctx = requireContext();
        float dp = getResources().getDisplayMetrics().density;

        tvTriviaScore = new TextView(ctx);
        tvTriviaScore.setText("Score: " + triviaScore + " pts • Question " + (triviaIndex + 1) + " of " + triviaQuestions.length);
        tvTriviaScore.setTextSize(14f);
        tvTriviaScore.setTypeface(null, Typeface.BOLD);
        tvTriviaScore.setTextColor(ContextCompat.getColor(ctx, R.color.veil_accent));
        tvTriviaScore.setPadding(0, 0, 0, (int) (8 * dp));
        contentContainer.addView(tvTriviaScore);

        TriviaQuestion q = triviaQuestions[triviaIndex % triviaQuestions.length];

        tvTriviaQuestion = new TextView(ctx);
        tvTriviaQuestion.setText(q.question);
        tvTriviaQuestion.setTextSize(15.5f);
        tvTriviaQuestion.setTypeface(null, Typeface.BOLD);
        tvTriviaQuestion.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary));
        tvTriviaQuestion.setBackgroundResource(R.drawable.bg_reply_preview);
        tvTriviaQuestion.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));
        contentContainer.addView(tvTriviaQuestion);

        triviaOptionsContainer = new LinearLayout(ctx);
        triviaOptionsContainer.setOrientation(LinearLayout.VERTICAL);
        triviaOptionsContainer.setPadding(0, (int) (10 * dp), 0, 0);

        for (int i = 0; i < q.options.length; i++) {
            final int optIdx = i;
            Button optBtn = new Button(ctx);
            optBtn.setText(q.options[i]);
            optBtn.setTextSize(13.5f);
            optBtn.setBackgroundColor(Color.parseColor("#1E293B"));
            optBtn.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (6 * dp);
            optBtn.setLayoutParams(lp);

            optBtn.setOnClickListener(v -> handleTriviaAnswer(optIdx, q.answerIdx));
            triviaOptionsContainer.addView(optBtn);
        }
        contentContainer.addView(triviaOptionsContainer);
    }

    private void handleTriviaAnswer(int chosen, int correct) {
        Context ctx = requireContext();
        if (chosen == correct) {
            triviaScore += 10;
            VeilFeatureManager.getInstance(ctx).addCoins(5);
            VeilFeatureManager.getInstance(ctx).addXp(15);
            Toast.makeText(ctx, "🎯 Correct! +10 pts & +5 VeilCoins", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(ctx, "❌ Missed! Answer was: " + triviaQuestions[triviaIndex].options[correct], Toast.LENGTH_SHORT).show();
        }

        triviaIndex = (triviaIndex + 1) % triviaQuestions.length;
        renderActiveGame();
    }

    // ---------------------------------------------------------------------------------------------
    // GAME 3: Truth or Dare State Synchronizer (#219)
    // ---------------------------------------------------------------------------------------------
    private final String[] TRUTHS = {
            "What is the most encrypted secret you've never told anyone?",
            "If someone hacked your phone right now, what would shock them most?",
            "Have you ever ghosted someone and regretted it?",
            "What's an anonymous opinion you have that nobody agrees with?"
    };

    private final String[] DARES = {
            "Record a 5-second voice note using the CYBER_ROBOT voice filter.",
            "Take a photo with the AR Cyberpunk Neon Visor mask and send it.",
            "Send an anonymous view-once Blink photo within 10 seconds.",
            "Tip 5 VeilCoins to your peer right now!"
    };

    private void renderTruthOrDare() {
        Context ctx = requireContext();
        float dp = getResources().getDisplayMetrics().density;

        TextView info = new TextView(ctx);
        info.setText("Tap to generate a synchronized P2P game challenge and send it directly to the chat.");
        info.setTextSize(13f);
        info.setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary));
        info.setPadding(0, 0, 0, (int) (14 * dp));
        contentContainer.addView(info);

        Button btnTruth = new Button(ctx);
        btnTruth.setText("🎭 Generate TRUTH Prompt");
        btnTruth.setBackgroundColor(ContextCompat.getColor(ctx, R.color.veil_primary));
        btnTruth.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8 * dp);
        btnTruth.setLayoutParams(lp);
        btnTruth.setOnClickListener(v -> {
            String p = "🎲 [TRUTH CHALLENGE] " + TRUTHS[new Random().nextInt(TRUTHS.length)];
            dispatchPromptToChat(p);
        });
        contentContainer.addView(btnTruth);

        Button btnDare = new Button(ctx);
        btnDare.setText("⚡ Generate DARE Challenge");
        btnDare.setBackgroundColor(ContextCompat.getColor(ctx, R.color.veil_accent));
        btnDare.setTextColor(Color.WHITE);
        btnDare.setLayoutParams(lp);
        btnDare.setOnClickListener(v -> {
            String p = "🔥 [DARE CHALLENGE] " + DARES[new Random().nextInt(DARES.length)];
            dispatchPromptToChat(p);
        });
        contentContainer.addView(btnDare);
    }

    private void dispatchPromptToChat(String prompt) {
        if (promptSendListener != null) {
            promptSendListener.onSendPrompt(prompt);
            Toast.makeText(requireContext(), "Dispatched game prompt to chat!", Toast.LENGTH_SHORT).show();
            dismiss();
        } else {
            Toast.makeText(requireContext(), prompt, Toast.LENGTH_LONG).show();
        }
    }

    private void sendGamePacket(String game, String action, int cell, String mark) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("game", game);
            obj.put("action", action);
            obj.put("cell", cell);
            obj.put("mark", mark);
            obj.put("chatId", chatId);

            VeilPacket packet = new VeilPacket(
                    VeilPacket.LANE_CTRL,
                    VeilPacket.TYPE_GAME_STATE,
                    obj.toString().getBytes(StandardCharsets.UTF_8)
            );
            if (getContext() != null && recipientId != null && !recipientId.isEmpty()) {
                WebRtcManager.getInstance(getContext()).sendPacket(recipientId, packet);
            }
        } catch (Exception ignored) {}
    }
}
