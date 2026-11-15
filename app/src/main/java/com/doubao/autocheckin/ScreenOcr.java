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
 * 屏幕文字识别：把截图交给 ML Kit 离线中文模型，返回带坐标的文字块。
 * 坐标就是屏幕像素坐标，可以直接拿来 dispatchGesture 点击。
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

    public interface Callback {
        void onDone(List<Word> words, String error);
    }

    public static void recognize(Bitmap bitmap, final Callback cb) {
        if (bitmap == null) {
            cb.onDone(new ArrayList<Word>(), "截图为空");
            return;
        }
        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            recognizer().process(image)
                    .addOnSuccessListener(result -> cb.onDone(flatten(result), null))
                    .addOnFailureListener(e -> cb.onDone(new ArrayList<Word>(), String.valueOf(e)));
        } catch (Throwable t) {
            cb.onDone(new ArrayList<Word>(), String.valueOf(t));
        }
    }

    private static List<Word> flatten(Text result) {
        List<Word> out = new ArrayList<>();
        if (result == null) {
            return out;
        }
        for (Text.TextBlock block : result.getTextBlocks()) {
            List<Text.Element> elements = block.getElements();
            if (elements != null && !elements.isEmpty()) {
                for (Text.Element el : elements) {
                    String t = el.getText();
                    if (t != null && !t.trim().isEmpty() && el.getBoundingBox() != null) {
                        out.add(new Word(t, el.getBoundingBox()));
                    }
                }
            } else {
                for (Text.Line line : block.getLines()) {
                    String t = line.getText();
                    if (t != null && !t.trim().isEmpty() && line.getBoundingBox() != null) {
                        out.add(new Word(t, line.getBoundingBox()));
                    }
                }
            }
        }
        return out;
    }
}
