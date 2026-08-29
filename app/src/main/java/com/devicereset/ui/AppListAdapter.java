package com.devicereset.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.devicereset.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 应用列表适配器，支持搜索过滤和勾选。
 */
public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {
    private final List<AppInfo> originalList;
    private List<AppInfo> filteredList;
    private final OnAppCheckedListener listener;

    public interface OnAppCheckedListener {
        void onAppChecked(AppInfo appInfo, boolean checked);
    }

    public AppListAdapter(List<AppInfo> appList, OnAppCheckedListener listener) {
        this.originalList = new ArrayList<>(appList);
        this.filteredList = new ArrayList<>(appList);
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppInfo app = filteredList.get(position);
        holder.icon.setImageDrawable(app.icon);
        holder.appName.setText(app.appName);
        holder.packageName.setText(app.packageName);
        holder.checkBox.setChecked(app.isSelected);

        String systemTag = app.isSystemApp ? " [系统]" : "";
        holder.packageName.setText(app.packageName + systemTag);

        holder.itemView.setOnClickListener(v -> {
            holder.checkBox.setChecked(!holder.checkBox.isChecked());
        });

        holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            app.isSelected = isChecked;
            if (listener != null) {
                listener.onAppChecked(app, isChecked);
            }
        });
    }

    @Override
    public int getItemCount() {
        return filteredList.size();
    }

    /**
     * 搜索过滤。
     */
    public void filter(String query) {
        if (query == null || query.isEmpty()) {
            filteredList = new ArrayList<>(originalList);
        } else {
            String lowerQuery = query.toLowerCase(Locale.ROOT);
            filteredList = new ArrayList<>();
            for (AppInfo app : originalList) {
                if (app.appName.toLowerCase(Locale.ROOT).contains(lowerQuery)
                        || app.packageName.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                    filteredList.add(app);
                }
            }
        }
        notifyDataSetChanged();
    }

    /**
     * 更新原始列表（比如切换显示系统应用后）。
     */
    public void updateList(List<AppInfo> newList) {
        originalList.clear();
        originalList.addAll(newList);
        filteredList = new ArrayList<>(newList);
        notifyDataSetChanged();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView appName;
        TextView packageName;
        CheckBox checkBox;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.app_icon);
            appName = itemView.findViewById(R.id.app_name);
            packageName = itemView.findViewById(R.id.app_package);
            checkBox = itemView.findViewById(R.id.app_checkbox);
        }
    }
}
