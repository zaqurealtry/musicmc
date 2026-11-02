package com.spotifymc.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import com.spotifymc.api.SpotifyPlaybackState;
import com.spotifymc.config.SpotifyConfig;
import com.spotifymc.lyrics.Lyrics;
import com.spotifymc.lyrics.LyricsService;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Places synchronized lyrics on a billboard in the 3D world in front of the local player. */
public final class WorldLyricsRenderer {
    private final SpotifyConfig config;
    private final LyricsService lyricsService;
    private final com.spotifymc.playback.SpotifyPlaybackManager playbackManager;

    public WorldLyricsRenderer(SpotifyConfig config,
                               com.spotifymc.playback.SpotifyPlaybackManager playbackManager) {
        this.config = config;
        this.playbackManager = playbackManager;
        this.lyricsService = playbackManager.getLyricsService();
    }

    public void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(this::collect);
    }

    private void collect(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context) {
        if (!config.isLyricsEnabled() || config.getLyricsStyle() != SpotifyConfig.LyricsStyle.SKY) return;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null || mc.options.hideGui) return;

        SpotifyPlaybackState state = playbackManager.getCurrentState();
        if (!state.hasTrack() || state.status() != SpotifyPlaybackState.Status.PLAYING) return;
        LyricsService.Result result = lyricsService.resultFor(state);
        if (result.status() != LyricsService.Status.FOUND || result.lyrics() == null || result.lyrics().instrumental()) return;

        Lyrics lyrics = result.lyrics();
        long position = state.getExtrapolatedProgressMs() + config.getLyricsOffsetMs();
        int index = lyrics.indexAt(position, state.durationMs());
        String current = lyrics.textAt(index);
        String next = config.getLyricsUpcomingLines() > 0 ? lyrics.textAt(index + 1) : null;
        if ((current == null || current.isBlank()) && (next == null || next.isBlank())) return;

        Font font = mc.font;
        List<Line> lines = new ArrayList<>();
        if (current != null && !current.isBlank()) lines.add(new Line(current, 0xFFFFFFFF, true));
        if (next != null && !next.isBlank()) lines.add(new Line(next, 0xFFB9D7CA, false));
        if (lines.isEmpty()) return;

        Vec3 cameraPos = mc.gameRenderer.getMainCamera().position();
        Vec3 target = player.getEyePosition().add(player.getViewVector(1.0f).scale(5.0)).add(0, 0.55, 0);
        Vec3 relative = target.subtract(cameraPos);

        PoseStack pose = context.poseStack();
        SubmitNodeCollector collector = context.submitNodeCollector();
        pose.pushPose();
        pose.translate(relative.x, relative.y, relative.z);
        pose.mulPose(Axis.YP.rotationDegrees(-player.getYRot()));
        pose.mulPose(Axis.XP.rotationDegrees(player.getXRot()));
        pose.scale(-0.018f, -0.018f, 0.018f);

        float widest = 0;
        List<FormattedCharSequence> formatted = new ArrayList<>();
        for (Line line : lines) {
            FormattedCharSequence sequence = Component.literal(line.text()).getVisualOrderText();
            formatted.add(sequence);
            widest = Math.max(widest, font.width(sequence));
        }

        float padding = 18;
        float rowHeight = 18;
        float panelWidth = widest + padding * 2;
        float panelHeight = lines.size() * rowHeight + 16;
        float left = -panelWidth / 2;
        float top = -panelHeight / 2;
        collector.submitTextBackground(pose, left, top, left + panelWidth, top + panelHeight,
                (int) (Math.clamp(config.getHudOpacity(), 0.25f, 1.0f) * 190) << 24 | 0x0010151A,
                Font.DisplayMode.NORMAL, 0x00F000F0);

        float y = top + 9;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            FormattedCharSequence text = formatted.get(i);
            float x = -font.width(text) / 2.0f;
            collector.submitText(pose, x, y, text, true, Font.DisplayMode.NORMAL,
                    0x00F000F0, line.color(), 0, 0);
            y += rowHeight;
        }
        pose.popPose();
    }

    private record Line(String text, int color, boolean current) { }
}
