package com.m3.veil.ui.onboard;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.m3.veil.R;

import java.util.List;

public class OnboardingAdapter extends RecyclerView.Adapter<OnboardingAdapter.OnboardingViewHolder> {

    private final List<OnboardingItem> onboardingItems;

    public OnboardingAdapter(List<OnboardingItem> onboardingItems) {
        this.onboardingItems = onboardingItems;
    }

    @NonNull
    @Override
    public OnboardingViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_onboarding, parent, false);
        return new OnboardingViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull OnboardingViewHolder holder, int position) {
        OnboardingItem item = onboardingItems.get(position);
        holder.title.setText(item.getTitle());
        holder.description.setText(item.getDescription());
        holder.image.setImageResource(item.getImageResId());

        // Top-right micro-pill
        holder.pillTopRightText.setText(item.getTopRightPill());
        startFloatingAnimation(holder.pillTopRightCard, -8f, 2200L);

        // Bottom-left micro-pill (Only shown on Slide 1: "Local Key")
        if (item.getBottomLeftPill() != null && !item.getBottomLeftPill().isEmpty()) {
            holder.pillBottomLeftCard.setVisibility(View.VISIBLE);
            holder.pillBottomLeftText.setText(item.getBottomLeftPill());
            startFloatingAnimation(holder.pillBottomLeftCard, 8f, 2500L);
        } else {
            holder.pillBottomLeftCard.clearAnimation();
            holder.pillBottomLeftCard.setVisibility(View.GONE);
        }
    }

    private void startFloatingAnimation(View target, float deltaY, long duration) {
        ObjectAnimator animator = ObjectAnimator.ofFloat(target, View.TRANSLATION_Y, 0f, deltaY);
        animator.setDuration(duration);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.start();
    }

    @Override
    public int getItemCount() {
        return onboardingItems.size();
    }

    static class OnboardingViewHolder extends RecyclerView.ViewHolder {
        private final ImageView image;
        private final TextView title;
        private final TextView description;
        private final MaterialCardView pillTopRightCard;
        private final TextView pillTopRightText;
        private final MaterialCardView pillBottomLeftCard;
        private final TextView pillBottomLeftText;

        public OnboardingViewHolder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.onboardingImage);
            title = itemView.findViewById(R.id.onboardingTitle);
            description = itemView.findViewById(R.id.onboardingDescription);
            pillTopRightCard = itemView.findViewById(R.id.pillTopRightCard);
            pillTopRightText = itemView.findViewById(R.id.pillTopRightText);
            pillBottomLeftCard = itemView.findViewById(R.id.pillBottomLeftCard);
            pillBottomLeftText = itemView.findViewById(R.id.pillBottomLeftText);
        }
    }
}