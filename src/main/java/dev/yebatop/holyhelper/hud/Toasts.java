package dev.yebatop.holyhelper.hud;

import dev.yebatop.holyhelper.HolyHelperClient;
import dev.yebatop.holyhelper.core.ServerDetector;
import dev.yebatop.holyhelper.liteapi.FeatureGate;
import dev.yebatop.holyhelper.ui.Fonts;
import dev.yebatop.holyhelper.ui.Motion;
import dev.yebatop.holyhelper.ui.Paint;
import dev.yebatop.holyhelper.ui.Surface;
import dev.yebatop.holyhelper.ui.Theme;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Короткие сообщения о том, что мод что-то прочитал.
 * <p>
 * Мод читает чужие окна молча, и до этого понять, запомнил он что-нибудь или нет,
 * было неоткуда: открыл Скупца, закрыл — и гадай. Теперь снимок виден сразу и
 * подписан тем, что в нём оказалось.
 * <p>
 * Внизу справа, а не вверху, где их рисует клиент: сверху справа висит сайдбар
 * сервера, и лезть туда мод не должен. Слева внизу чат.
 */
public final class Toasts {

    public static final String FEATURE = "toasts";

    /** Сколько живёт сообщение и сколько длятся въезд и уход. */
    private static final long LIFE = 3_200L;
    private static final long SLIDE = 260L;

    private static final int WIDTH = 152;
    private static final int HEIGHT = 25;
    private static final int MARGIN = 4;
    private static final int GAP = 3;
    private static final int RADIUS = 4;

    /** Больше трёх на экране — это уже спам, а не подсказка. */
    private static final int MAX = 3;

    private record Toast(String title, String detail, int accent, long bornAt) {
    }

    private static final Deque<Toast> TOASTS = new ArrayDeque<>();

    private Toasts() {
    }

    public static void register() {
        HudElementRegistry.addLast(
                Identifier.of(HolyHelperClient.MOD_ID, "toasts"),
                Toasts::render);
    }

    /** Показать сообщение. Одинаковое подряд не дублируется. */
    public static void push(String title, String detail, int accent) {
        Toast last = TOASTS.peekLast();
        if (last != null && last.title().equals(title) && last.detail().equals(detail)) {
            return;
        }
        TOASTS.addLast(new Toast(title, detail, accent, System.currentTimeMillis()));
        while (TOASTS.size() > MAX) {
            TOASTS.removeFirst();
        }
    }

    public static void clear() {
        TOASTS.clear();
    }

    private static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        HolyHelperClient mod = HolyHelperClient.instance();

        if (mod == null || client.player == null || client.options.hudHidden || TOASTS.isEmpty()) {
            return;
        }
        if (!mod.config().toastsEnabled || !ServerDetector.onHolyWorld()) {
            TOASTS.clear();
            return;
        }
        if (mod.featureGate().status() == FeatureGate.Status.ANSWERED
                && !mod.featureGate().isAllowed(FEATURE)) {
            TOASTS.clear();
            return;
        }

        long now = System.currentTimeMillis();
        TOASTS.removeIf(toast -> now - toast.bornAt() > LIFE);

        List<Toast> visible = new ArrayList<>(TOASTS);
        int right = Surface.units(ctx.getScaledWindowWidth()) - MARGIN;
        int bottom = Surface.units(ctx.getScaledWindowHeight()) - MARGIN;

        Surface.open(ctx);
        try {
            paint(ctx, client.textRenderer, visible, right, bottom, now);
        } finally {
            Surface.close(ctx);
        }
    }

    /** Сами сообщения, уже в единицах мода. */
    private static void paint(DrawContext ctx, TextRenderer font, List<Toast> visible,
                              int right, int bottom, long now) {
        for (int i = visible.size() - 1; i >= 0; i--) {
            Toast toast = visible.get(i);
            long age = now - toast.bornAt();

            // Въезд справа и уход туда же: сообщение приходит и уходит одним движением,
            // а не мигает на месте.
            double in = Motion.reveal(age, 0, SLIDE);
            double out = age > LIFE - SLIDE
                    ? 1 - Motion.ease((age - (LIFE - SLIDE)) / (double) SLIDE)
                    : 1;
            double shown = Math.min(in, out);
            if (shown <= 0) {
                continue;
            }

            int y = bottom - (visible.size() - i) * (HEIGHT + GAP);
            int x = right - WIDTH + Motion.rise(shown, WIDTH / 2);
            draw(ctx, font, toast, x, y, shown);
        }
    }

    private static void draw(DrawContext ctx, TextRenderer font, Toast toast,
                             int x, int y, double alpha) {
        Paint.panel(ctx, x, y, WIDTH, HEIGHT, RADIUS, Theme.PANEL_SOLID, alpha);
        Paint.sheen(ctx, x, y, WIDTH, RADIUS, System.currentTimeMillis(), 8_000L, alpha);
        Paint.accent(ctx, x, y, HEIGHT, toast.accent(), alpha);

        Fonts.draw(ctx, font, toast.title(), Fonts.BODY, x + 10, y + 5,
                Motion.fade(toast.accent(), alpha));
        Fonts.label(ctx, font, toast.detail(), x + 10, y + 15,
                Motion.fade(Theme.TEXT_FAINT, alpha));
    }
}
