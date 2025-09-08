package com.fc.freer.ui;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.fc.freer.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 自定义结果视图，用于显示网络测试结果
 */
public class ResultView extends LinearLayout {
    
    private TextView titleTextView;
    private TextView contentTextView;
    private TextView timestampTextView;
    private LinearLayout resultsContainer;
    
    private List<TestResult> results = new ArrayList<>();
    private SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    
    public ResultView(Context context) {
        super(context);
        init(context);
    }
    
    public ResultView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }
    
    public ResultView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }
    
    private void init(Context context) {
        setOrientation(VERTICAL);
        setBackgroundColor(Color.WHITE);
        
        // 加载布局
        LayoutInflater.from(context).inflate(R.layout.view_result, this, true);
        
        // 初始化视图
        titleTextView = findViewById(R.id.result_title);
        contentTextView = findViewById(R.id.result_content);
        timestampTextView = findViewById(R.id.result_timestamp);
        resultsContainer = findViewById(R.id.results_container);
        
        // 设置初始标题
        setTitle("网络测试结果");
    }
    
    /**
     * 设置标题
     */
    public void setTitle(String title) {
        if (titleTextView != null) {
            titleTextView.setText(title);
        }
    }
    
    /**
     * 添加测试结果
     */
    public void addResult(String testName, boolean success, String message) {
        TestResult result = new TestResult(testName, success, message, System.currentTimeMillis());
        results.add(result);
        updateDisplay();
    }
    
    /**
     * 添加成功结果
     */
    public void addSuccess(String testName, String message) {
        addResult(testName, true, message);
    }
    
    /**
     * 添加失败结果
     */
    public void addFailure(String testName, String message) {
        addResult(testName, false, message);
    }
    
    /**
     * 清除所有结果
     */
    public void clearResults() {
        results.clear();
        updateDisplay();
    }
    
    /**
     * 更新显示
     */
    private void updateDisplay() {
        if (resultsContainer == null) return;
        
        resultsContainer.removeAllViews();
        
        for (TestResult result : results) {
            View resultView = createResultItem(result);
            resultsContainer.addView(resultView);
        }
        
        // 更新内容摘要
        updateSummary();
    }
    
    /**
     * 创建单个结果项
     */
    private View createResultItem(TestResult result) {
        View itemView = LayoutInflater.from(getContext()).inflate(R.layout.item_test_result, null);
        
        TextView nameTextView = itemView.findViewById(R.id.test_name);
        TextView statusTextView = itemView.findViewById(R.id.test_status);
        TextView messageTextView = itemView.findViewById(R.id.test_message);
        TextView timeTextView = itemView.findViewById(R.id.test_time);
        
        nameTextView.setText(result.testName);
        statusTextView.setText(result.success ? "✓" : "✗");
        int accentColor = ContextCompat.getColor(getContext(), R.color.colorAccent);
        int errorColor = ContextCompat.getColor(getContext(), R.color.error);
        statusTextView.setTextColor(result.success ? accentColor : errorColor);
        messageTextView.setText(result.message);
        timeTextView.setText(dateFormat.format(new Date(result.timestamp)));
        
        return itemView;
    }
    
    /**
     * 更新摘要信息
     */
    private void updateSummary() {
        if (contentTextView == null) return;
        
        int total = results.size();
        int success = 0;
        int failure = 0;
        
        for (TestResult result : results) {
            if (result.success) {
                success++;
            } else {
                failure++;
            }
        }
        
        String summary = String.format("总计: %d | 成功: %d | 失败: %d", total, success, failure);
        contentTextView.setText(summary);
        
        // 更新时间戳
        if (timestampTextView != null) {
            timestampTextView.setText("最后更新: " + dateFormat.format(new Date()));
        }
    }
    
    /**
     * 获取结果统计
     */
    public ResultStats getStats() {
        int total = results.size();
        int success = 0;
        int failure = 0;
        
        for (TestResult result : results) {
            if (result.success) {
                success++;
            } else {
                failure++;
            }
        }
        
        return new ResultStats(total, success, failure);
    }
    
    /**
     * 测试结果数据类
     */
    public static class TestResult {
        public final String testName;
        public final boolean success;
        public final String message;
        public final long timestamp;
        
        public TestResult(String testName, boolean success, String message, long timestamp) {
            this.testName = testName;
            this.success = success;
            this.message = message;
            this.timestamp = timestamp;
        }
    }
    
    /**
     * 结果统计类
     */
    public static class ResultStats {
        public final int total;
        public final int success;
        public final int failure;
        
        public ResultStats(int total, int success, int failure) {
            this.total = total;
            this.success = success;
            this.failure = failure;
        }
    }
} 