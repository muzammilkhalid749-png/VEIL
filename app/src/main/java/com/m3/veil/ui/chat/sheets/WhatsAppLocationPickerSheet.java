package com.m3.veil.ui.chat.sheets;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.card.MaterialCardView;
import com.m3.veil.R;
import com.m3.veil.ui.views.WhatsAppMapPreviewView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * WhatsApp-style in-app Send Location Picker BottomSheet.
 * Features:
 * - Interactive map preview canvas with live GPS pin and accuracy halo
 * - "Share live location" row (15 min / 1 hour / 8 hours)
 * - "Send your current location (Accurate to X m)" row
 * - Searchable "Nearby places" list
 */
public class WhatsAppLocationPickerSheet extends BottomSheetDialogFragment {

    public interface LocationPickerListener {
        void onLocationSelectedToSend(String geoUriString);
    }

    public static class NearbyPlace {
        public final String name;
        public final String categoryAndAddress;
        public final double latOffset;
        public final double lngOffset;

        public NearbyPlace(String name, String categoryAndAddress, double latOffset, double lngOffset) {
            this.name = name;
            this.categoryAndAddress = categoryAndAddress;
            this.latOffset = latOffset;
            this.lngOffset = lngOffset;
        }
    }

    private LocationPickerListener listener;
    private WhatsAppMapPreviewView mapPreviewView;
    private TextView tvCurrentAccuracy;
    private LinearLayout nearbyPlacesContainer;
    private double currentLat = 37.7749;
    private double currentLng = -122.4194;
    private float currentAccuracy = 8.0f;
    private String searchQuery = "";
    private final List<NearbyPlace> places = new ArrayList<>();

    public void setLocationPickerListener(LocationPickerListener listener) {
        this.listener = listener;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetDialog d = (BottomSheetDialog) getDialog();
            View sheet = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                behavior.setSkipCollapsed(true);
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        float dp = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_bottom_sheet);
        root.setPadding(0, (int) (12 * dp), 0, (int) (18 * dp));

        // Drag handle
        View handle = new View(ctx);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams((int) (40 * dp), (int) (4 * dp));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (10 * dp);
        handle.setLayoutParams(handleLp);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(Color.parseColor("#CBD5E1"));
        handleBg.setCornerRadius(4 * dp);
        handle.setBackground(handleBg);
        root.addView(handle);

        // Header Row ("Send location" + Close)
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding((int) (20 * dp), 0, (int) (20 * dp), (int) (10 * dp));

        TextView tvTitle = new TextView(ctx);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tvTitle.setText("Send location");
        tvTitle.setTextSize(19f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(ctx.getResources().getColor(R.color.textPrimary));
        header.addView(tvTitle);

        TextView btnClose = new TextView(ctx);
        btnClose.setText("✕");
        btnClose.setTextSize(18f);
        btnClose.setPadding((int) (8 * dp), (int) (4 * dp), (int) (8 * dp), (int) (4 * dp));
        btnClose.setTextColor(ctx.getResources().getColor(R.color.textSecondary));
        btnClose.setOnClickListener(v -> dismiss());
        header.addView(btnClose);
        root.addView(header);

        // Interactive WhatsApp Map Frame
        MaterialCardView mapCard = new MaterialCardView(ctx);
        LinearLayout.LayoutParams mapCardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (185 * dp));
        mapCardLp.setMargins((int) (16 * dp), 0, (int) (16 * dp), (int) (10 * dp));
        mapCard.setLayoutParams(mapCardLp);
        mapCard.setRadius(14 * dp);
        mapCard.setCardElevation(0f);

        FrameLayout mapFrame = new FrameLayout(ctx);
        mapPreviewView = new WhatsAppMapPreviewView(ctx);
        mapFrame.addView(mapPreviewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Recenter GPS Pill Button on Map
        TextView btnRecenter = new TextView(ctx);
        FrameLayout.LayoutParams recenterLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        recenterLp.setMargins((int) (10 * dp), (int) (10 * dp), (int) (10 * dp), (int) (10 * dp));
        btnRecenter.setLayoutParams(recenterLp);
        btnRecenter.setText("🎯 My GPS");
        btnRecenter.setTextSize(11.5f);
        btnRecenter.setTypeface(null, Typeface.BOLD);
        btnRecenter.setTextColor(Color.parseColor("#111B21"));
        btnRecenter.setPadding((int) (10 * dp), (int) (6 * dp), (int) (10 * dp), (int) (6 * dp));
        GradientDrawable recenterBg = new GradientDrawable();
        recenterBg.setCornerRadius(16 * dp);
        recenterBg.setColor(Color.WHITE);
        btnRecenter.setBackground(recenterBg);
        btnRecenter.setElevation(3 * dp);
        btnRecenter.setOnClickListener(v -> refreshDeviceGps(ctx));
        mapFrame.addView(btnRecenter);

        mapCard.addView(mapFrame);
        root.addView(mapCard);

        // Row 1: Share live location (WhatsApp Style)
        LinearLayout liveRow = new LinearLayout(ctx);
        liveRow.setOrientation(LinearLayout.HORIZONTAL);
        liveRow.setGravity(Gravity.CENTER_VERTICAL);
        liveRow.setPadding((int) (18 * dp), (int) (10 * dp), (int) (18 * dp), (int) (10 * dp));
        liveRow.setBackgroundResource(R.drawable.bg_filter_chip);
        LinearLayout.LayoutParams liveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        liveLp.setMargins((int) (16 * dp), 0, (int) (16 * dp), (int) (8 * dp));
        liveRow.setLayoutParams(liveLp);

        FrameLayout liveBadge = new FrameLayout(ctx);
        liveBadge.setLayoutParams(new LinearLayout.LayoutParams((int) (40 * dp), (int) (40 * dp)));
        GradientDrawable liveCircle = new GradientDrawable();
        liveCircle.setShape(GradientDrawable.OVAL);
        liveCircle.setColor(Color.parseColor("#00A884"));
        liveBadge.setBackground(liveCircle);
        ImageView liveIcon = new ImageView(ctx);
        liveIcon.setLayoutParams(new FrameLayout.LayoutParams((int) (20 * dp), (int) (20 * dp), Gravity.CENTER));
        liveIcon.setImageResource(R.drawable.ic_location);
        liveIcon.setColorFilter(Color.WHITE);
        liveBadge.addView(liveIcon);
        liveRow.addView(liveBadge);

        LinearLayout liveTextCol = new LinearLayout(ctx);
        liveTextCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ltLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ltLp.leftMargin = (int) (12 * dp);
        liveTextCol.setLayoutParams(ltLp);

        TextView tvLiveTitle = new TextView(ctx);
        tvLiveTitle.setText("Share live location");
        tvLiveTitle.setTextSize(15.5f);
        tvLiveTitle.setTypeface(null, Typeface.BOLD);
        tvLiveTitle.setTextColor(ctx.getResources().getColor(R.color.textPrimary));
        liveTextCol.addView(tvLiveTitle);

        TextView tvLiveSub = new TextView(ctx);
        tvLiveSub.setText("Real-time P2P encrypted location updates");
        tvLiveSub.setTextSize(12f);
        tvLiveSub.setTextColor(ctx.getResources().getColor(R.color.textSecondary));
        liveTextCol.addView(tvLiveSub);
        liveRow.addView(liveTextCol);

        liveRow.setOnClickListener(v -> showLiveLocationDurationDialog(ctx));
        root.addView(liveRow);

        // Row 2: Send your current location (WhatsApp Style)
        LinearLayout currentRow = new LinearLayout(ctx);
        currentRow.setOrientation(LinearLayout.HORIZONTAL);
        currentRow.setGravity(Gravity.CENTER_VERTICAL);
        currentRow.setPadding((int) (18 * dp), (int) (10 * dp), (int) (18 * dp), (int) (10 * dp));
        currentRow.setBackgroundResource(R.drawable.bg_filter_chip);
        LinearLayout.LayoutParams currLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        currLp.setMargins((int) (16 * dp), 0, (int) (16 * dp), (int) (10 * dp));
        currentRow.setLayoutParams(currLp);

        FrameLayout currBadge = new FrameLayout(ctx);
        currBadge.setLayoutParams(new LinearLayout.LayoutParams((int) (40 * dp), (int) (40 * dp)));
        GradientDrawable currCircle = new GradientDrawable();
        currCircle.setShape(GradientDrawable.OVAL);
        currCircle.setColor(Color.parseColor("#008069"));
        currBadge.setBackground(currCircle);
        ImageView currIcon = new ImageView(ctx);
        currIcon.setLayoutParams(new FrameLayout.LayoutParams((int) (20 * dp), (int) (20 * dp), Gravity.CENTER));
        currIcon.setImageResource(R.drawable.ic_location);
        currIcon.setColorFilter(Color.WHITE);
        currBadge.addView(currIcon);
        currentRow.addView(currBadge);

        LinearLayout currTextCol = new LinearLayout(ctx);
        currTextCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ctLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ctLp.leftMargin = (int) (12 * dp);
        currTextCol.setLayoutParams(ctLp);

        TextView tvCurrTitle = new TextView(ctx);
        tvCurrTitle.setText("Send your current location");
        tvCurrTitle.setTextSize(15.5f);
        tvCurrTitle.setTypeface(null, Typeface.BOLD);
        tvCurrTitle.setTextColor(ctx.getResources().getColor(R.color.textPrimary));
        currTextCol.addView(tvCurrTitle);

        tvCurrentAccuracy = new TextView(ctx);
        tvCurrentAccuracy.setText("Accurate to 8 meters");
        tvCurrentAccuracy.setTextSize(12f);
        tvCurrentAccuracy.setTextColor(ctx.getResources().getColor(R.color.textSecondary));
        currTextCol.addView(tvCurrentAccuracy);
        currentRow.addView(currTextCol);

        currentRow.setOnClickListener(v -> {
            String geo = String.format(Locale.US,
                    "geo:%.6f,%.6f?q=%.6f,%.6f(Current Location - Accurate to %.0f m)",
                    currentLat, currentLng, currentLat, currentLng, currentAccuracy);
            if (listener != null) listener.onLocationSelectedToSend(geo);
            dismiss();
        });
        root.addView(currentRow);

        // Nearby places header & search
        TextView tvNearbyHeader = new TextView(ctx);
        tvNearbyHeader.setText("Nearby places");
        tvNearbyHeader.setTextSize(13f);
        tvNearbyHeader.setTypeface(null, Typeface.BOLD);
        tvNearbyHeader.setTextColor(ctx.getResources().getColor(R.color.textSecondary));
        tvNearbyHeader.setPadding((int) (20 * dp), (int) (2 * dp), (int) (20 * dp), (int) (6 * dp));
        root.addView(tvNearbyHeader);

        EditText etSearch = new EditText(ctx);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (38 * dp));
        searchLp.setMargins((int) (16 * dp), 0, (int) (16 * dp), (int) (6 * dp));
        etSearch.setLayoutParams(searchLp);
        etSearch.setBackgroundResource(R.drawable.bg_chat_input);
        etSearch.setHint("Search nearby places...");
        etSearch.setTextSize(13.5f);
        etSearch.setSingleLine(true);
        etSearch.setPadding((int) (14 * dp), 0, (int) (14 * dp), 0);
        etSearch.setTextColor(ctx.getResources().getColor(R.color.textPrimary));
        etSearch.setHintTextColor(ctx.getResources().getColor(R.color.textSecondary));
        root.addView(etSearch);

        ScrollView scrollView = new ScrollView(ctx);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (190 * dp)));
        nearbyPlacesContainer = new LinearLayout(ctx);
        nearbyPlacesContainer.setOrientation(LinearLayout.VERTICAL);
        nearbyPlacesContainer.setPadding((int) (16 * dp), 0, (int) (16 * dp), (int) (10 * dp));
        scrollView.addView(nearbyPlacesContainer);
        root.addView(scrollView);

        initNearbyPlaces();
        refreshDeviceGps(ctx);
        renderNearbyPlaces();

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s != null ? s.toString().trim().toLowerCase(Locale.ROOT) : "";
                renderNearbyPlaces();
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        return root;
    }

    private void showLiveLocationDurationDialog(Context ctx) {
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        float dp = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        root.setPadding((int) (24 * dp), (int) (12 * dp), (int) (24 * dp), (int) (28 * dp));

        View handle = new View(ctx);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams((int) (36 * dp), (int) (4 * dp));
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        hLp.bottomMargin = (int) (16 * dp);
        handle.setLayoutParams(hLp);
        handle.setBackgroundColor(ContextCompat.getColor(ctx, R.color.colorOutline));
        root.addView(handle);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText("🟢 Share Live Location");
        tvTitle.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary));
        tvTitle.setTextSize(20f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvTitle.setPadding(0, 0, 0, (int) (4 * dp));
        root.addView(tvTitle);

        TextView tvSub = new TextView(ctx);
        tvSub.setText("Select duration to stream your real-time GPS coordinates over P2P mesh:");
        tvSub.setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary));
        tvSub.setTextSize(13f);
        tvSub.setPadding(0, 0, 0, (int) (18 * dp));
        root.addView(tvSub);

        String[] durations = {"15 minutes", "1 hour", "8 hours"};
        String[] descriptions = {
                "Quick meet-up or brief transit tracking",
                "Standard session for commuting or city travel",
                "Full day expedition with continuous encrypted P2P broadcast"
        };
        String[] icons = {"⚡", "⏱️", "🧭"};

        for (int i = 0; i < durations.length; i++) {
            final String dur = durations[i];

            MaterialCardView card = new MaterialCardView(ctx);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (10 * dp);
            card.setLayoutParams(lp);
            card.setRadius(12 * dp);
            card.setCardElevation(0);
            card.setStrokeWidth((int) (1 * dp));
            card.setStrokeColor(ContextCompat.getColor(ctx, R.color.colorOutline));
            card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.surfaceVariant));

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (14 * dp), (int) (14 * dp), (int) (14 * dp), (int) (14 * dp));

            TextView tvIcon = new TextView(ctx);
            tvIcon.setText(icons[i]);
            tvIcon.setTextSize(22f);
            tvIcon.setPadding(0, 0, (int) (14 * dp), 0);
            row.addView(tvIcon);

            LinearLayout textCol = new LinearLayout(ctx);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            textCol.setLayoutParams(tcLp);

            TextView tvDur = new TextView(ctx);
            tvDur.setText(dur);
            tvDur.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary));
            tvDur.setTextSize(15.5f);
            tvDur.setTypeface(null, Typeface.BOLD);
            textCol.addView(tvDur);

            TextView tvDesc = new TextView(ctx);
            tvDesc.setText(descriptions[i]);
            tvDesc.setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary));
            tvDesc.setTextSize(12f);
            textCol.addView(tvDesc);
            row.addView(textCol);

            ImageView arrow = new ImageView(ctx);
            arrow.setImageResource(R.drawable.ic_arrow_forward);
            arrow.setColorFilter(ContextCompat.getColor(ctx, R.color.textSecondary));
            LinearLayout.LayoutParams arrLp = new LinearLayout.LayoutParams((int) (18 * dp), (int) (18 * dp));
            arrow.setLayoutParams(arrLp);
            row.addView(arrow);

            card.addView(row);
            card.setOnClickListener(v -> {
                sheet.dismiss();
                String geo = String.format(Locale.US,
                        "geo:%.6f,%.6f?q=%.6f,%.6f(Live Location (%s) - Real-time P2P GPS)",
                        currentLat, currentLng, currentLat, currentLng, dur);
                if (listener != null) listener.onLocationSelectedToSend(geo);
                dismiss();
            });

            root.addView(card);
        }

        sheet.setContentView(root);
        sheet.show();
    }

    private void initNearbyPlaces() {
        places.clear();
        places.add(new NearbyPlace("Central Coffee Roasters", "Cafe • 120m away", 0.0009, 0.0007));
        places.add(new NearbyPlace("Metro Transit Hub", "Station • 240m away", -0.0014, 0.0011));
        places.add(new NearbyPlace("Emerald City Park", "Park & Recreation • 310m away", 0.0018, -0.0012));
        places.add(new NearbyPlace("Skyline Tech Plaza", "Co-working & Lounge • 450m away", -0.0021, -0.0016));
        places.add(new NearbyPlace("24/7 Pharmacy & Medical", "Health • 520m away", 0.0025, 0.0019));
        places.add(new NearbyPlace("University Library Square", "Campus • 680m away", -0.0031, 0.0024));
    }

    private void refreshDeviceGps(Context ctx) {
        try {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) {
                    Location loc = null;
                    if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    }
                    if (loc == null && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    }
                    if (loc != null) {
                        updateCoordinates(loc.getLatitude(), loc.getLongitude(), loc.hasAccuracy() ? loc.getAccuracy() : 6f);
                    } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, new LocationListener() {
                            @Override
                            public void onLocationChanged(@NonNull Location l) {
                                updateCoordinates(l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : 8f);
                            }
                            @Override
                            public void onStatusChanged(String provider, int status, Bundle extras) {}
                            @Override
                            public void onProviderEnabled(@NonNull String provider) {}
                            @Override
                            public void onProviderDisabled(@NonNull String provider) {}
                        }, Looper.getMainLooper());
                    }
                }
            }
        } catch (Exception ignored) {}
        updateCoordinates(currentLat, currentLng, currentAccuracy);
    }

    private void updateCoordinates(double lat, double lng, float acc) {
        this.currentLat = lat;
        this.currentLng = lng;
        this.currentAccuracy = Math.max(3f, acc);
        if (mapPreviewView != null) {
            mapPreviewView.setLocationData(currentLat, currentLng, false, currentAccuracy);
        }
        if (tvCurrentAccuracy != null) {
            tvCurrentAccuracy.setText(String.format(Locale.US,
                    "Accurate to %.0f meters • %.5f, %.5f", currentAccuracy, currentLat, currentLng));
        }
    }

    private void renderNearbyPlaces() {
        if (nearbyPlacesContainer == null || getContext() == null) return;
        Context ctx = getContext();
        float dp = ctx.getResources().getDisplayMetrics().density;
        nearbyPlacesContainer.removeAllViews();

        for (NearbyPlace p : places) {
            if (!searchQuery.isEmpty()
                    && !p.name.toLowerCase(Locale.ROOT).contains(searchQuery)
                    && !p.categoryAndAddress.toLowerCase(Locale.ROOT).contains(searchQuery)) {
                continue;
            }

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (10 * dp), (int) (9 * dp), (int) (10 * dp), (int) (9 * dp));
            row.setBackgroundResource(R.drawable.bg_doc_inner_card);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (6 * dp);
            row.setLayoutParams(lp);

            FrameLayout pinIconBox = new FrameLayout(ctx);
            pinIconBox.setLayoutParams(new LinearLayout.LayoutParams((int) (34 * dp), (int) (34 * dp)));
            GradientDrawable pinBg = new GradientDrawable();
            pinBg.setShape(GradientDrawable.OVAL);
            pinBg.setColor(Color.parseColor("#2200A884"));
            pinIconBox.setBackground(pinBg);
            ImageView iv = new ImageView(ctx);
            iv.setLayoutParams(new FrameLayout.LayoutParams((int) (18 * dp), (int) (18 * dp), Gravity.CENTER));
            iv.setImageResource(R.drawable.ic_location);
            iv.setColorFilter(Color.parseColor("#008069"));
            pinIconBox.addView(iv);
            row.addView(pinIconBox);

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cLp.leftMargin = (int) (12 * dp);
            col.setLayoutParams(cLp);

            TextView tvName = new TextView(ctx);
            tvName.setText(p.name);
            tvName.setTextSize(14.5f);
            tvName.setTypeface(null, Typeface.BOLD);
            tvName.setTextColor(ctx.getResources().getColor(R.color.textPrimary));
            col.addView(tvName);

            TextView tvAddr = new TextView(ctx);
            tvAddr.setText(p.categoryAndAddress);
            tvAddr.setTextSize(12f);
            tvAddr.setTextColor(ctx.getResources().getColor(R.color.textSecondary));
            col.addView(tvAddr);
            row.addView(col);

            row.setOnClickListener(v -> {
                double pLat = currentLat + p.latOffset;
                double pLng = currentLng + p.lngOffset;
                String geo = String.format(Locale.US,
                        "geo:%.6f,%.6f?q=%.6f,%.6f(%s - %s)",
                        pLat, pLng, pLat, pLng, p.name, p.categoryAndAddress);
                if (listener != null) listener.onLocationSelectedToSend(geo);
                dismiss();
            });

            nearbyPlacesContainer.addView(row);
        }
    }
}
