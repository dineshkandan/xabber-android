package com.xabber.android.ui.adapter;

import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.xabber.android.R;
import com.xabber.android.data.database.messagerealm.Attachment;
import com.xabber.android.data.filedownload.DownloadManager;

import org.apache.commons.io.FileUtils;

import io.realm.RealmList;
import rx.functions.Action1;
import rx.subscriptions.CompositeSubscription;

public class FilesAdapter extends RecyclerView.Adapter<FilesAdapter.FileViewHolder> {

    private RealmList<Attachment> items;
    private FileListListener listener;

    interface FileListListener {
        void onFileClick(int position);
    }

    public FilesAdapter(RealmList<Attachment> items, FileListListener listener) {
        this.items = items;
        this.listener = listener;
    }

    @Override
    public FileViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file_message, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(final FileViewHolder holder, final int position) {
        Attachment attachment = items.get(position);

        holder.attachmentId = attachment.getUniqueId();

        // set file icon
        holder.tvFileName.setText(attachment.getTitle());
        Long size = attachment.getFileSize();
        holder.tvFileSize.setText(FileUtils.byteCountToDisplaySize(size != null ? size : 0));
        holder.ivFileIcon.setImageResource(attachment.getFilePath() != null ? R.drawable.ic_file : R.drawable.ic_download);

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onFileClick(position);
            }
        });

        holder.itemView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                holder.subscribeForDownloadProgress();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                holder.unsubscribeAll();
            }
        });
    }



    @Override
    public int getItemCount() {
        return items.size();
    }

    class FileViewHolder extends RecyclerView.ViewHolder {

        private CompositeSubscription subscriptions = new CompositeSubscription();
        String attachmentId;

        final TextView tvFileName;
        final TextView tvFileSize;
        final ImageView ivFileIcon;
        final ProgressBar progressBar;
        final ImageButton ivCancelDownload;

        public FileViewHolder(View itemView) {
            super(itemView);
            tvFileName = itemView.findViewById(R.id.tvFileName);
            tvFileSize = itemView.findViewById(R.id.tvFileSize);
            ivFileIcon = itemView.findViewById(R.id.ivFileIcon);
            progressBar = itemView.findViewById(R.id.progressBar);
            ivCancelDownload = itemView.findViewById(R.id.ivCancelDownload);
        }

        public void unsubscribeAll() {
            subscriptions.clear();
        }

        public void subscribeForDownloadProgress() {
            subscriptions.add(DownloadManager.getInstance().subscribeForProgress()
                .doOnNext(new Action1<DownloadManager.ProgressData>() {
                    @Override
                    public void call(DownloadManager.ProgressData progressData) {
                        setUpProgress(progressData);
                    }
                }).subscribe());
        }

        private void setUpProgress(DownloadManager.ProgressData progressData) {
            if (progressData != null && progressData.getAttachmentId().equals(attachmentId)) {
                if (progressData.isCompleted()) {
                    showProgress(false);
                } else if (progressData.getError() != null) {
                    showProgress(false);
                    // show error
                } else {
                    progressBar.setProgress(progressData.getProgress());
                    showProgress(true);
                }
            } else showProgress(false);
        }

        private void showProgress(boolean show) {
            if (show) {
                progressBar.setVisibility(View.VISIBLE);
                ivCancelDownload.setVisibility(View.VISIBLE);
                ivFileIcon.setVisibility(View.GONE);
            } else {
                progressBar.setVisibility(View.GONE);
                ivCancelDownload.setVisibility(View.GONE);
                ivFileIcon.setVisibility(View.VISIBLE);
            }
        }

    }

}
