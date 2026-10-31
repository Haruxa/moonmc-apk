package cat.narezany.horizon;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * MoonMC: экран первой загрузки — знак проекта, текущий шаг подготовки и полоса прогресса.
 *
 * Отдельное окно (Dialog), потому что поверх нативной поверхности (NativeActivity) обычные
 * view не видны: SurfaceView рисуется под окном. Диалог же лежит выше и всегда на экране.
 */
final class MoonSplash extends Dialog {
    private final Splash view;

    MoonSplash(Context context) {
        super(context, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        view = new Splash(context);
        setContentView(view);
        setCancelable(false);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF070A0D));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
            window.setDimAmount(0f);
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /** Экран уходит сам, если игра уже стартовала и шагов больше нет. */
    private final Runnable idleHide = new Runnable() {
        @Override
        public void run() {
            finishQuietly();
        }
    };

    /** Следующий шаг подготовки ("Копирую ресурсы…", "Распаковываю 42%"…). */
    void step(String text) {
        view.step(text);
        view.removeCallbacks(idleHide);
        view.postDelayed(idleHide, 25000L);
    }

    void finishQuietly() {
        try {
            dismiss();
        } catch (RuntimeException e) {
            // окно уже закрыто — нечего делать
        }
    }

    /** Сам экран: всё нарисовано кодом, без ресурсов и разметки. */
    static final class Splash extends View {
        private static final int MINT = 0xFF9BE8BE;
        private static final int PAPER = 0xFFF3F8F5;
        private static final int DIM = 0xFF8CA0A7;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mark = new Path();
        private final Path cut = new Path();
        private final Path clip = new Path();
        private final RectF bar = new RectF();
        private final long started = SystemClock.uptimeMillis();
        private final String version;

        private String step = "";
        private float percent = -1f;

        Splash(Context context) {
            super(context);
            setBackgroundColor(0xFF070A0D);
            String name = "";
            try {
                name = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            } catch (Exception e) {
                // без версии — просто имя
            }
            version = name == null ? "" : name;
        }

        void step(String value) {
            step = value == null ? "" : value.replace('\n', ' ').trim();
            percent = -1f;
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{1,3})\\s*%").matcher(step);
            if (m.find()) {
                percent = Math.min(100, Integer.parseInt(m.group(1))) / 100f;
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            float seconds = (SystemClock.uptimeMillis() - started) / 1000f;
            float cx = w / 2f;
            float unit = Math.min(w, h);
            float cy = h * 0.36f;
            float r = unit * 0.082f;

            // фон и мягкое свечение за знаком
            paint.setShader(new LinearGradient(0, 0, 0, h, 0xFF0C1418, 0xFF05070A,
                Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(new RadialGradient(cx, cy, r * 4.6f,
                new int[] { 0x3077FFD0, 0x0D77FFD0, 0x00000000 },
                new float[] { 0f, 0.55f, 1f }, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r * 4.6f, paint);
            paint.setShader(null);

            // знак: месяц — круг минус смещённый круг; медленно «дышит»
            float pulse = 1f + 0.014f * (float) Math.sin(seconds * 1.5f);
            mark.reset();
            mark.addCircle(cx, cy, r * pulse, Path.Direction.CW);
            cut.reset();
            cut.addCircle(cx + r * 0.46f, cy - r * 0.24f, r * 0.88f * pulse, Path.Direction.CW);
            mark.op(cut, Path.Op.DIFFERENCE);
            paint.setColor(MINT);
            canvas.drawPath(mark, paint);

            // имя проекта
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(PAPER);
            text.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            text.setTextSize(unit * 0.072f);
            text.setLetterSpacing(0.13f);
            canvas.drawText("MoonMC", cx, cy + r * 3.1f, text);

            text.setColor(DIM);
            text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            text.setTextSize(unit * 0.025f);
            text.setLetterSpacing(0.26f);
            canvas.drawText("MINECRAFT  BEDROCK  EDITION", cx, cy + r * 4.2f, text);
            text.setLetterSpacing(0f);

            // полоса: с процентом — заполнение, без — бегущий блик
            float bw = Math.min(w * 0.44f, unit * 0.60f);
            float bh = Math.max(5f, unit * 0.0085f);
            float by = cy + r * 5.9f;
            bar.set(cx - bw / 2f, by, cx + bw / 2f, by + bh);
            paint.setColor(0x1FFFFFFF);
            canvas.drawRoundRect(bar, bh / 2f, bh / 2f, paint);
            if (percent >= 0f) {
                paint.setColor(MINT);
                canvas.drawRoundRect(cx - bw / 2f, by,
                    cx - bw / 2f + Math.max(bh, bw * percent), by + bh, bh / 2f, bh / 2f, paint);
            } else {
                canvas.save();
                clip.reset();
                clip.addRoundRect(bar, bh / 2f, bh / 2f, Path.Direction.CW);
                canvas.clipPath(clip);
                float sw = bw * 0.34f;
                float phase = (seconds * 0.5f) % 1f;
                float ease = phase * phase * (3f - 2f * phase);
                float sx = cx - bw / 2f - sw + (bw + sw) * ease;
                paint.setShader(new LinearGradient(sx, 0f, sx + sw, 0f,
                    new int[] { 0x009BE8BE, MINT, 0x009BE8BE },
                    new float[] { 0f, 0.5f, 1f }, Shader.TileMode.CLAMP));
                canvas.drawRect(sx, by, sx + sw, by + bh, paint);
                paint.setShader(null);
                canvas.restore();
            }

            // текущий шаг
            text.setColor(0xFFB9C8CE);
            text.setTextSize(unit * 0.027f);
            String line = step.isEmpty() ? "Preparing the game" : step;
            if (line.length() > 76) {
                line = line.substring(0, 75) + "…";
            }
            canvas.drawText(line, cx, by + bh + unit * 0.062f, text);

            // низ: имя и версия
            text.setColor(0x5EFFFFFF);
            text.setTextSize(unit * 0.020f);
            text.setLetterSpacing(0.12f);
            canvas.drawText(version.isEmpty() ? "MoonMC" : "MoonMC  " + version,
                cx, h - unit * 0.045f, text);
            text.setLetterSpacing(0f);

            if (percent < 0f) {
                postInvalidateOnAnimation();
            }
        }
    }
}
