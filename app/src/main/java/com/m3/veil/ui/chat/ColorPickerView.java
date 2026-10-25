package com.m3.veil.ui.chat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class ColorPickerView extends View {

    private Paint paint;
    private RectF rectF;
    private int[] colors;
    private OnColorChangeListener onColorChangeListener;

    public interface OnColorChangeListener {
        void onColorChanged(int color);
    }

    public ColorPickerView(Context context) {
        super(context);
        init();
    }

    public ColorPickerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ColorPickerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private Paint strokePaint;
    private Paint indicatorPaint;
    private float currentY = -1;

    private void init() {
        paint = new Paint();
        paint.setAntiAlias(true);
        
        strokePaint = new Paint();
        strokePaint.setAntiAlias(true);
        strokePaint.setColor(Color.WHITE);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(4f);
        
        indicatorPaint = new Paint();
        indicatorPaint.setAntiAlias(true);
        indicatorPaint.setColor(Color.WHITE);
        indicatorPaint.setStyle(Paint.Style.FILL);
        indicatorPaint.setShadowLayer(4f, 0, 2f, Color.BLACK);
        
        // This is required for shadow layer to work
        setLayerType(LAYER_TYPE_SOFTWARE, indicatorPaint);
        
        rectF = new RectF();
        
        // Colors for the gradient
        colors = new int[]{
                Color.WHITE,
                Color.RED,
                Color.YELLOW,
                Color.GREEN,
                Color.CYAN,
                Color.BLUE,
                Color.MAGENTA,
                Color.BLACK
        };
    }

    public void setOnColorChangeListener(OnColorChangeListener listener) {
        this.onColorChangeListener = listener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        // Add padding for stroke
        float padding = strokePaint.getStrokeWidth();
        rectF.set(padding, padding, getWidth() - padding, getHeight() - padding);
        
        LinearGradient gradient = new LinearGradient(
                0, padding, 0, getHeight() - padding,
                colors,
                null,
                Shader.TileMode.CLAMP);
                
        paint.setShader(gradient);
        
        // Draw gradient
        float radius = getWidth() / 2f;
        canvas.drawRoundRect(rectF, radius, radius, paint);
        
        // Draw white stroke
        canvas.drawRoundRect(rectF, radius, radius, strokePaint);
        
        // Draw indicator
        if (currentY != -1) {
            float indicatorRadius = getWidth() / 2f + 2f;
            canvas.drawCircle(getWidth() / 2f, currentY, indicatorRadius, indicatorPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float y = event.getY();
        if (y < 0) y = 0;
        if (y > getHeight()) y = getHeight();

        currentY = y;
        invalidate();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                int selectedColor = getColorAtY(y);
                if (onColorChangeListener != null) {
                    onColorChangeListener.onColorChanged(selectedColor);
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    private int getColorAtY(float y) {
        float position = y / getHeight();
        if (position <= 0) return colors[0];
        if (position >= 1) return colors[colors.length - 1];

        float exactIndex = position * (colors.length - 1);
        int index1 = (int) exactIndex;
        int index2 = Math.min(index1 + 1, colors.length - 1);
        float fraction = exactIndex - index1;

        int c1 = colors[index1];
        int c2 = colors[index2];

        int a = (int) (Color.alpha(c1) + fraction * (Color.alpha(c2) - Color.alpha(c1)));
        int r = (int) (Color.red(c1) + fraction * (Color.red(c2) - Color.red(c1)));
        int g = (int) (Color.green(c1) + fraction * (Color.green(c2) - Color.green(c1)));
        int b = (int) (Color.blue(c1) + fraction * (Color.blue(c2) - Color.blue(c1)));

        return Color.argb(a, r, g, b);
    }
}
