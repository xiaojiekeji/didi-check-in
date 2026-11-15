package com.doubao.autocheckin;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * 屏幕文字识别：把截图交给 ML Kit 离线中文模型，返回带坐标的文字行。
 * 坐标就是屏幕像素坐标，可以直接拿来 dispatchGesture 点击。
 *
 * 速度要点：支持只识别屏幕的某一块区域（ROI），区域越小越快。
 */
public final class ScreenOcr {

    /** 一块识别出来的文字（或一个带文字的控件）。 */
    public static final class Word {
        public final String text;
        public final Rect box;

        public Word(String text, Rect box) {
            this.text = text == null ? "" : text.trim();
            this.box = box == null ? new Rect() : box;
        }

        public int centerX() {
            return box.centerX();
        }

        public int centerY() {
            return box.centerY();
        }

        public int height() {
            return box.height();
        }

        @Override
        public String toString() {
            return "\"" + text + "\"@" + box;
        }
    }

    private static TextRecognizer recognizer;

    private ScreenOcr() {
    }

    private static synchronized TextRecognizer recognizer() {
        if (recognizer == null) {
            recognizer = TextRecognition.getClient(
                    new ChineseTextRecognizerOptions.Builder().build());
        }
        return recognizer;
    }

    /** 预热模型：服务连上就先加载，避免第一次识别多等 1 秒左右。 */
    public static void warmup() {
        try {
            recognizer();
        } catch (Throwable ignored) {
        }
    }

    public interface Callback {
        void onDone(List<Word> words, String error);
    }

    public static void recognize(Bitmap bitmap, final Callback cb) {
        recognize(bitmap, null, cb);
    }

    /**
     * 识别指定区域（roi 为 null 时识别整张图）。返回的坐标已换算回整屏坐标。
     */
    public static void recognize(Bitmap bitmap, Rect roi, final Callback cb) {
        if (bitmap == null) {
            cb.onDone(new ArrayList<Word>(), "截图为空");
            return;
        }
        Bitmap cropped = null;
        int ox = 0;
        int oy = 0;
        try {
            int imgW = bitmap.getWidth();
            int imgH = bitmap.getHeight();
            if (roi != null) {
                int left = Math.max(0, Math.min(roi.left, imgW - 1));
                int top = Math.max(0, Math.min(roi.top, imgH - 1));
                int right = Math.min(imgW, Math.max(roi.right, left + 1));
                int bottom = Math.min(imgH, Math.max(roi.bottom, top + 1));
                if (right - left > 8 && bottom - top > 8) {
                    cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top);
                    ox = left;
                    oy = top;
                }
            }
            InputImage image = InputImage.fromBitmap(cropped != null ? cropped : bitmap, 0);
            final int fx = ox;
            final int fy = oy;
            recognizer().process(image)
                    .addOnSuccessListener(result -> cb.onDone(flatten(result, fx, fy), null))
                    .addOnFailureListener(e -> cb.onDone(new ArrayList<Word>(), String.valueOf(e)));
        } catch (Throwable t) {
            cb.onDone(new ArrayList<Word>(), String.valueOf(t));
        }
    }

    private static List<Word> flatten(Text result, int ox, int oy) {
        List<Word> out = new ArrayList<>();
        if (result == null) {
            return out;
        }
        for (Text.TextBlock block : result.getTextBlocks()) {
            if (block == null) {
                continue;
            }
            List<Text.Line> lines = block.getLines();
            if (lines == null) {
                continue;
            }
            for (Text.Line line : lines) {
                if (line == null) {
                    continue;
                }
                String t = line.getText();
                Rect b = line.getBoundingBox();
                if (t == null || t.trim().isEmpty() || b == null) {
                    continue;
                }
                Rect moved = new Rect(b.left + ox, b.top + oy, b.right + ox, b.bottom + oy);
                out.add(new Word(t, moved));
            }
        }
        return out;
    }
}
