package com.pbakondy;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import java.io.File;
import java.util.concurrent.CompletableFuture;

public class AudioConverter {

    private final Context context;

    public AudioConverter(Context context) {
        this.context = context;
    }

    /**
     * Converts an AMR audio file to MP4 with AAC encoding
     *
     * @param inputPath  Path to the input AMR file
     * @param outputPath Path where the output MP4 file will be saved
     * @return CompletableFuture containing ExportResult
     */
    @OptIn(markerClass = UnstableApi.class)
    public CompletableFuture<ExportResult> convert(String inputPath, String outputPath) {
        CompletableFuture<ExportResult> future = new CompletableFuture<>();

        // Create output file parent directories if needed
        File outputFile = new File(outputPath);
        if (outputFile.getParentFile() != null) {
            outputFile.getParentFile().mkdirs();
        }

        // Create MediaItem from input AMR file
        MediaItem mediaItem = MediaItem.fromUri(new File(inputPath).toURI().toString());

        // Create EditedMediaItem
        EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem)
                .setRemoveVideo(true)
                .setEffects(Effects.EMPTY)
                .build();


        // Set up listener for conversion progress
        Transformer.Listener listener = new Transformer.Listener() {
            @Override
            public void onCompleted(@NonNull Composition composition, @NonNull ExportResult exportResult) {
                future.complete(exportResult);
            }

            @Override
            public void onError(@NonNull Composition composition, @NonNull ExportResult exportResult,
                                @NonNull ExportException exportException) {
                future.completeExceptionally(exportException);
            }
        };

        // Build Transformer
        Transformer transformer = new Transformer.Builder(context)
                .addListener(listener)
                .setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)
                .setEncoderFactory(
                        new DefaultEncoderFactory.Builder(context)
                                .setEnableFallback(true)
                                .setEnableCodecDbLite(false)
                                .setRequestedVideoEncoderSettings(VideoEncoderSettings.DEFAULT)
                                .build())
                .build();

        // Start conversion
        transformer.start(editedMediaItem, outputPath);

        return future;
    }
}
