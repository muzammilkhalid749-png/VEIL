package com.m3.veil.ui.auth;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import com.m3.veil.R;

public class CountryAdapter extends RecyclerView.Adapter<CountryAdapter.CountryViewHolder> {

    public List<Country> countries;
    private OnCountryClickListener listener;

    public interface OnCountryClickListener {
        void onCountryClick(Country country);
    }

    public CountryAdapter(List<Country> countries, OnCountryClickListener listener) {
        this.countries = new ArrayList<>(countries);
        this.listener = listener;
    }

    public void updateCountries(List<Country> newCountries) {
        this.countries.clear();
        this.countries.addAll(newCountries);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public CountryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_country, parent, false);
        return new CountryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CountryViewHolder holder, int position) {
        Country country = countries.get(position);
        holder.flagEmoji.setText(country.getFlag());
        holder.countryName.setText(country.getName());
        holder.countryCode.setText(country.getDialCode());

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onCountryClick(country);
            }
        });
    }

    @Override
    public int getItemCount() {
        return countries.size();
    }

    static class CountryViewHolder extends RecyclerView.ViewHolder {
        TextView flagEmoji, countryName, countryCode;

        public CountryViewHolder(@NonNull View itemView) {
            super(itemView);
            flagEmoji = itemView.findViewById(R.id.flagEmoji);
            countryName = itemView.findViewById(R.id.countryName);
            countryCode = itemView.findViewById(R.id.countryCode);
        }
    }
}