package cat.narezany.horizon;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MoonMC: экран первой загрузки в духе Material 3 Expressive — знак проекта, текущий шаг
 * подготовки и загрузка фигурами (морфящиеся круги-квадраты), на фоне падают звёзды.
 *
 * Отдельное окно (Dialog), потому что поверх нативной поверхности (NativeActivity) обычные
 * view не видны: SurfaceView рисуется под окном. Диалог лежит выше и всегда на экране.
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
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF06080B));
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

    /** Сам экран: всё нарисовано кодом, без разметки и ресурсов. */
    static final class Splash extends View {
        private static final int MINT = 0xFF9BE8BE;
        private static final int MINT_SOFT = 0xFFCFF5E0;
        private static final int INK = 0xFF0A1116;
        private static final int PAPER = 0xFFF4F9F6;
        private static final int DIM = 0xFF8DA1A8;
        private static final int STARS = 46;
        private static final Pattern PERCENT = Pattern.compile("(\\d{1,3})\\s*%");

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mark = new Path();
        private final Path cut = new Path();
        private final RectF box = new RectF();
        private final RectF ring = new RectF();
        private final float[] starX = new float[STARS];
        private final float[] starY = new float[STARS];
        private final float[] starR = new float[STARS];
        private final float[] starSpeed = new float[STARS];
        private final float[] starPhase = new float[STARS];
        private final float[] starDrift = new float[STARS];
        private final long started = SystemClock.uptimeMillis();
        private final String version;
        private final String engine;
        private final Bitmap logo;

        private String step = "";
        private float percent = -1f;
        private final List<String> lines = new ArrayList<>();

        Splash(Context context) {
            super(context);
            setBackgroundColor(0xFF06080B);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            Random random = new Random(20261031L);
            for (int i = 0; i < STARS; i++) {
                // x, y — в долях экрана; скорость — долей экрана в секунду
                starX[i] = random.nextFloat();
                starY[i] = random.nextFloat();
                starR[i] = 0.5f + random.nextFloat() * 1.9f;
                starSpeed[i] = 0.012f + random.nextFloat() * 0.055f;
                starPhase[i] = random.nextFloat() * 6.283f;
                starDrift[i] = (random.nextFloat() - 0.5f) * 0.02f;
            }
            String name = "";
            try {
                name = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            } catch (Exception e) {
                // без версии — просто имя
            }
            version = name == null ? "" : name;
            // Пиксельная луна из папки «иконки» (ci/branding/drawable-nodpi/moonmc_logo.png)
            Bitmap bitmap = null;
            try {
                int id = context.getResources().getIdentifier(
                    "moonmc_logo", "drawable", context.getPackageName());
                if (id != 0) {
                    bitmap = BitmapFactory.decodeResource(context.getResources(), id);
                }
            } catch (RuntimeException | OutOfMemoryError e) {
                // без картинки нарисуем месяц кодом
            }
            logo = bitmap;
            // на каком движке собрано: строку пишет CI (android/res/values/engine.xml)
            String build = "";
            try {
                int id = context.getResources().getIdentifier(
                    "moonmc_engine", "string", context.getPackageName());
                if (id != 0) build = context.getString(id);
            } catch (RuntimeException e) {
                // без подписи движка
            }
            engine = build;
        }

        void step(String value) {
            step = value == null ? "" : value.replace("\r", "").trim();
            percent = -1f;
            Matcher m = PERCENT.matcher(step);
            if (m.find()) {
                percent = Math.min(100, Integer.parseInt(m.group(1))) / 100f;
            }
            lines.clear();
            invalidate();
        }

        /** Все строки текста по ширине (без обрезки). */
        private void wrap(String source, float maxWidth) {
            lines.clear();
            for (String paragraph : source.split("\n")) {
                String rest = paragraph.trim();
                if (rest.isEmpty()) {
                    lines.add("");
                    continue;
                }
                while (!rest.isEmpty()) {
                    int fit = text.breakText(rest, true, maxWidth, null);
                    if (fit <= 0) fit = 1;
                    int end = Math.min(fit, rest.length());
                    if (end < rest.length()) {
                        int space = rest.lastIndexOf(' ', end);
                        if (space > 0) end = space;
                    }
                    lines.add(rest.substring(0, end).trim());
                    rest = rest.substring(Math.min(end, rest.length())).trim();
                }
            }
        }

        /** Обрезает последнюю строку многоточием, если текст не влез. */
        private void ellipsize(float maxWidth, int maxLines) {
            if (lines.size() <= maxLines) return;
            while (lines.size() > maxLines) lines.remove(lines.size() - 1);
            if (lines.isEmpty()) return;
            String last = lines.get(maxLines - 1);
            while (!last.isEmpty() && text.measureText(last + "…") > maxWidth) {
                last = last.substring(0, last.length() - 1);
            }
            lines.set(maxLines - 1, last + "…");
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            float seconds = (SystemClock.uptimeMillis() - started) / 1000f;
            float unit = Math.min(w, h);
            float cx = w / 2f;

            drawBackground(canvas, w, h, unit);
            drawStars(canvas, w, h, seconds);

            float logoY = h * 0.295f;
            float radius = unit * 0.055f;
            drawLogo(canvas, cx, logoY, radius, seconds);

            float titleBaseline = logoY + radius * 3.15f;
            drawTitle(canvas, cx, titleBaseline, unit);

            float loaderY = Math.max(titleBaseline + unit * 0.105f, h * 0.545f);
            drawLoader(canvas, cx, loaderY, unit, seconds);
            drawStep(canvas, cx, loaderY + unit * 0.048f, h - unit * 0.075f, w, unit);
            drawFooter(canvas, cx, h, w, unit);

            postInvalidateOnAnimation();
        }

        /** Фон: глубокая зелень в чёрное, плюс сияние за знаком. */
        private void drawBackground(Canvas canvas, int w, int h, float unit) {
            paint.setShader(new LinearGradient(0, 0, 0, h, 0xFF0A1612, 0xFF04070A,
                Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(new RadialGradient(w / 2f, h * 0.30f, unit * 0.60f,
                new int[] { 0x3363F2B8, 0x1422C08C, 0x00000000 },
                new float[] { 0f, 0.5f, 1f }, Shader.TileMode.CLAMP));
            canvas.drawCircle(w / 2f, h * 0.30f, unit * 0.60f, paint);
            paint.setShader(null);
        }

        /** Падающие звёзды: мелкие точки со шлейфом и мерцанием. */
        private void drawStars(Canvas canvas, int w, int h, float seconds) {
            for (int i = 0; i < STARS; i++) {
                float fall = (starY[i] + starSpeed[i] * seconds) % 1f;
                float y = fall * h;
                float x = (starX[i] + starDrift[i] * seconds) % 1f * w;
                if (x < 0) x += w;
                // мерцание и затухание к краям экрана
                float twinkle = 0.55f + 0.45f * (float) Math.sin(seconds * 1.7f + starPhase[i]);
                float edge = Math.min(1f, Math.min(y, h - y) / (h * 0.22f));
                float alpha = Math.max(0f, Math.min(1f, edge)) * twinkle;
                int a = (int) (alpha * 190f);
                if (a <= 4) continue;
                float r = starR[i];
                paint.setColor((a << 24) | 0xCFEFE4);
                canvas.drawCircle(x, y, r, paint);
                // шлейф: короткая полоска над звездой
                stroke.setColor(((a / 3) << 24) | 0x9BE8BE);
                stroke.setStrokeWidth(r * 0.9f);
                canvas.drawLine(x, y - r * 5.5f, x, y - r, stroke);
            }
        }

        /** Знак проекта: тёмная фигура «cookie ↔ circle» и пиксельная луна внутри. */
        private void drawLogo(Canvas canvas, float cx, float cy, float r, float seconds) {
            // появление с пружинкой (ease-out-back)
            float t = Math.min(1f, seconds / 0.65f);
            float u = t - 1f;
            float back = 1f + 2.70158f * u * u * u + 1.70158f * u * u;
            float appear = Math.max(0.001f, back);

            // морфинг формы: круг → скруглённый квадрат → круг (M3 Expressive shape morph)
            float morph = 0.5f + 0.5f * (float) Math.sin(seconds * 1.05f);
            float container = r * 1.72f * appear;
            box.set(cx - container, cy - container, cx + container, cy + container);
            float corner = container * (0.18f + 0.82f * (1f - morph));
            paint.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom,
                0xFF16362B, 0xFF0A1512, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(box, corner, corner, paint);
            paint.setShader(null);
            // мягкая мятная обводка фигуры
            stroke.setColor(0x3F9BE8BE);
            stroke.setStrokeWidth(Math.max(1f, r * 0.06f));
            canvas.drawRoundRect(box, corner, corner, stroke);

            if (logo != null) {
                // пиксель-арт: без сглаживания, с лёгким покачиванием и дыханием
                float breathe = 1f + 0.025f * (float) Math.sin(seconds * 1.7f);
                float side = container * 1.24f * breathe;
                float bob = (float) Math.sin(seconds * 1.15f) * r * 0.05f;
                box.set(cx - side / 2f, cy - side / 2f + bob,
                    cx + side / 2f, cy + side / 2f + bob);
                paint.setFilterBitmap(false);
                canvas.drawBitmap(logo, null, box, paint);
                return;
            }

            // запасной вариант: месяц кодом — круг минус смещённый круг
            float phase = seconds * 0.45f;
            float offset = r * 0.44f;
            float dx = (float) Math.cos(phase) * offset;
            float dy = (float) Math.sin(phase) * offset * 0.55f;
            mark.reset();
            mark.addCircle(cx, cy, r * 0.90f, Path.Direction.CW);
            cut.reset();
            cut.addCircle(cx + dx, cy + dy - r * 0.10f, r * 0.86f, Path.Direction.CW);
            mark.op(cut, Path.Op.DIFFERENCE);
            paint.setColor(MINT);
            canvas.drawPath(mark, paint);
        }

        /** Имя проекта и подпись. */
        private void drawTitle(Canvas canvas, float cx, float baseline, float unit) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(PAPER);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            text.setTextSize(unit * 0.062f);
            text.setLetterSpacing(0.01f);
            canvas.drawText("MoonMC", cx, baseline, text);

            text.setColor(DIM);
            text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            text.setTextSize(unit * 0.019f);
            text.setLetterSpacing(0.30f);
            canvas.drawText("MINECRAFT  BEDROCK  EDITION", cx, baseline + unit * 0.042f, text);
            text.setLetterSpacing(0f);
        }

        /**
         * Загрузка фигурами: три морфящиеся фигуры (круг ↔ скруглённый квадрат) «дышат»
         * со сдвигом по фазе. Если шаг сообщает процент — вместо них кольцо с числом.
         */
        private void drawLoader(Canvas canvas, float cx, float y, float unit, float seconds) {
            if (percent >= 0f) {
                float radius = unit * 0.026f;
                ring.set(cx - radius, y - radius, cx + radius, y + radius);
                stroke.setStrokeWidth(unit * 0.0075f);
                stroke.setColor(0x24FFFFFF);
                canvas.drawCircle(cx, y, radius, stroke);
                stroke.setColor(MINT);
                canvas.drawArc(ring, -90f, 360f * percent, false, stroke);
                text.setTextAlign(Paint.Align.CENTER);
                text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                text.setTextSize(unit * 0.020f);
                text.setColor(MINT);
                canvas.drawText(Math.round(percent * 100f) + "%", cx, y + unit * 0.007f, text);
                return;
            }

            float size = unit * 0.026f;
            float gap = size * 2.25f;
            int count = 3;
            float left = cx - gap * (count - 1) / 2f;
            for (int i = 0; i < count; i++) {
                float phase = seconds * 1.55f - i * 0.62f;
                float morph = 0.5f + 0.5f * (float) Math.sin(phase);       // 1 = круг, 0 = квадрат
                float breath = 0.80f + 0.24f * (float) Math.cos(phase * 0.85f);
                float lift = (float) Math.sin(phase * 0.9f) * size * 0.30f;
                float half = size * breath * 0.5f;
                float px = left + i * gap;
                float py = y - Math.abs(lift);
                box.set(px - half, py - half, px + half, py + half);
                float corner = half * (0.14f + 0.86f * morph);
                int alpha = 120 + (int) (115 * (0.5f + 0.5f * (float) Math.sin(phase)));
                paint.setColor((Math.min(255, alpha) << 24) | 0x9BE8BE);
                canvas.drawRoundRect(box, corner, corner, paint);
            }
        }

        /**
         * Текущий шаг: переносится по словам, а размер шрифта подбирается так, чтобы текст
         * влез целиком между фигурами загрузки и низом экрана. Не влезает — многоточие.
         */
        private void drawStep(Canvas canvas, float cx, float top, float bottom, float w,
                              float unit) {
            float maxWidth = Math.min(w * 0.70f, unit * 0.88f);
            float region = Math.max(unit * 0.05f, bottom - top);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            text.setColor(0xFFB7C7CD);
            String message = step.isEmpty() ? "Preparing the game…" : step;

            float[] scale = { 0.0230f, 0.0215f, 0.0200f, 0.0185f, 0.0170f, 0.0155f, 0.0140f };
            float size = scale[scale.length - 1];
            float lineHeight = size * unit * 1.42f;
            boolean fits = false;
            for (float candidate : scale) {
                text.setTextSize(candidate * unit);
                wrap(message, maxWidth);
                lineHeight = candidate * unit * 1.42f;
                if (lines.size() * lineHeight <= region) {
                    size = candidate;
                    fits = true;
                    break;
                }
                size = candidate;
            }
            text.setTextSize(size * unit);
            if (!fits) {
                wrap(message, maxWidth);
                lineHeight = size * unit * 1.42f;
                int room = Math.max(1, (int) (region / lineHeight));
                ellipsize(maxWidth, room);
            }
            float total = lines.size() * lineHeight;
            float startY = top + Math.max(0f, (region - total) / 2f);
            for (int i = 0; i < lines.size(); i++) {
                canvas.drawText(lines.get(i), cx,
                    startY + lineHeight * i + lineHeight * 0.72f, text);
            }
        }

        /** Низ экрана: имя, версия и движок (Nightly). */
        private void drawFooter(Canvas canvas, float cx, float h, float w, float unit) {
            String name = version.isEmpty() ? "MoonMC" : "MoonMC  " + version;
            String full = engine.isEmpty() ? name : name + "  ·  " + engine;
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(0x5CFFFFFF);
            text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            text.setLetterSpacing(0.14f);
            float size = unit * 0.018f;
            text.setTextSize(size);
            // не вылезаем за поля: сперва жмём шрифт, потом убираем версию
            while (size > unit * 0.011f && text.measureText(full) > w * 0.82f) {
                size -= unit * 0.0012f;
                text.setTextSize(size);
            }
            String line = full;
            if (text.measureText(line) > w * 0.82f && !engine.isEmpty()) line = engine;
            canvas.drawText(line, cx, h - unit * 0.052f, text);
            text.setLetterSpacing(0f);
        }
    }
}
