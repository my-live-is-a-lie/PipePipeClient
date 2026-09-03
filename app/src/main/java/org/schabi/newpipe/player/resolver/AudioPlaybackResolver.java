package org.schabi.newpipe.player.resolver;

import static org.schabi.newpipe.util.ListHelper.removeTorrentStreams;
import static org.schabi.newpipe.util.ListHelper.filterUnsupportedFormats;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.source.MediaSource;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.helper.PlayerHelper;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;
import org.schabi.newpipe.util.ListHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class AudioPlaybackResolver implements PlaybackResolver {
    private static final String TAG = AudioPlaybackResolver.class.getSimpleName();

    @NonNull
    private final Context context;
    @NonNull
    private final PlayerDataSource dataSource;
    private List<String> blacklistUrls = new ArrayList<>();
    @Nullable
    private String audioTrack;
    // Defaults to -1 (lowest) to preserve original behavior for any caller that never sets
    // this explicitly. Player.java sets this from the "Background playback audio quality"
    // setting before calling resolve() for background/audio-only playback.
    private int audioQualityTierKbps = -1;

    public AudioPlaybackResolver(@NonNull final Context context,
                                 @NonNull final PlayerDataSource dataSource) {
        this.context = context;
        this.dataSource = dataSource;
    }

    @Override
    @Nullable
    public MediaSource resolve(@NonNull final StreamInfo info) {
        final MediaSource liveSource = PlaybackResolver.maybeBuildLiveMediaSource(dataSource, info);
        if (liveSource != null) {
            return liveSource;
        }

        List<AudioStream> audioStreams = info.getAudioStreams()
                .stream().filter(s -> !blacklistUrls.contains(s.getContent())).collect(Collectors.toList());
        removeTorrentStreams(audioStreams);
        audioStreams = filterUnsupportedFormats(audioStreams, context);

        // Audio-only / background playback quality is controlled by the "Background
        // playback audio quality" setting (audioQualityTierKbps), defaulting to lowest to
        // save data and battery. If a specific audio track was requested, still honor the
        // track choice but pick within it according to the same quality tier.
        final int index;
        if (audioTrack != null) {
            final List<AudioStream> trackStreams = audioStreams.stream()
                    .filter(s -> audioTrack.equals(s.getAudioTrackId()))
                    .collect(Collectors.toList());
            final int inTrack = ListHelper.getAudioIndexForQualityTier(
                    audioQualityTierKbps, trackStreams);
            if (inTrack >= 0) {
                index = audioStreams.indexOf(trackStreams.get(inTrack));
            } else {
                index = ListHelper.getAudioIndexForQualityTier(audioQualityTierKbps, audioStreams);
            }
        } else {
            index = ListHelper.getAudioIndexForQualityTier(audioQualityTierKbps, audioStreams);
        }
        if (index < 0 || index >= audioStreams.size()) {
            return null;
        }

        final AudioStream audio = audioStreams.get(index);
        final String candidates = audioStreams.stream()
                .map(s -> s.getFormat() + "@" + s.getAverageBitrate() + "kbps")
                .collect(Collectors.joining(", "));
        Log.i(TAG, "Background/audio-only playback selected: "
                + audio.getFormat() + "@" + audio.getAverageBitrate() + "kbps"
                + " (candidates were: " + candidates + ")");
        final MediaItemTag tag = StreamInfoTag.of(info);

        try {
            return PlaybackResolver.buildMediaSource(
                    dataSource, audio, info, PlayerHelper.cacheKeyOf(info, audio), tag);
        } catch (final IOException e) {
            Log.e(TAG, "Unable to create audio source:", e);
            return null;
        }
    }
    public void addBlacklistUrl(@NonNull final String url) {
        blacklistUrls.add(url);
    }

    public List<String> getBlacklistUrls() {
        return blacklistUrls;
    }

    @Nullable
    public String getAudioTrack() {
        return audioTrack;
    }

    public void setAudioTrack(@Nullable final String audioTrack) {
        this.audioTrack = audioTrack;
    }

    /**
     * Sets the audio quality tier to use for background/audio-only playback: -1 for lowest,
     * -2 for highest, or a target bitrate in kbps. Defaults to -1 (lowest) if never called.
     */
    public void setAudioQualityTierKbps(final int audioQualityTierKbps) {
        this.audioQualityTierKbps = audioQualityTierKbps;
    }
}
