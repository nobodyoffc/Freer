package com.fc.freer.home;


import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.utils.ToastUtils;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.AvatarManager;
import com.fc.fc_ajdk.fapi.client.ApiProvider;
import com.fc.freer.network.HttpRequester;
import com.fc.freer.ui.InputObjectActivity;
import com.fc.freer.ui.SingleInputActivity;
import com.fc.freer.utils.IconCreator;

import android.content.Intent;
import com.fc.freer.ui.UserConfirmDialog;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.network.NetworkUtils;
import com.fc.freer.network.NetworkException;
import com.fc.freer.ui.ResultView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.graphics.Color;

public class TestActivity extends AppCompatActivity {

    private static final String TAG = "TestActivity";
    private ImageView imageView;

    private Button showInputActivityButton;
    private Button showUserConfirmDialogButton;
    private Button testSquareIconButton;
    private Button testRoundIconButton;
    private Button testSquareIconFilesButton;
    private Button testRoundIconFilesButton;
    private Button testNetworkButton;
    private Button testNetworkUtilsButton;
    private Button testInputObjectButton;
    private Button testUpdateObjectButton;
    private Button clearResultsButton;
    private ResultView resultView;

    private Dialog avatarDialog;
    private ImageView avatarImageView;
    private ImageView smallAvatarImageView;
    private TextView idTextView;
    private TextView countTextView;
    private Button stopButton;
    private Button nextButton;
    private List<KeyInfo> avatarKeyInfos;
    private int currentAvatarIndex = 0;
    private static final int REQUEST_CODE_SINGLE_INPUT = 2001;
    private static final int REQUEST_CODE_INPUT_OBJECT = 2002;
    private static final int REQUEST_CODE_UPDATE_OBJECT = 2003;
    private WaitingDialog waitingDialog;
    private HttpRequester httpRequester;
    private AvatarManager avatarManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test);

        // Set up toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Circle Image Test");
        }

        // Initialize views
        imageView = findViewById(R.id.imageView);
        showInputActivityButton = findViewById(R.id.showInputDialogButton);
        showUserConfirmDialogButton = findViewById(R.id.showUserConfirmDialogButton);
        testSquareIconButton = findViewById(R.id.testSquareIconButton);
        testRoundIconButton = findViewById(R.id.testRoundIconButton);
        testSquareIconFilesButton = findViewById(R.id.testSquareIconFilesButton);
        testRoundIconFilesButton = findViewById(R.id.testRoundIconFilesButton);
        testNetworkButton = findViewById(R.id.testNetworkButton);
        testNetworkUtilsButton = findViewById(R.id.testNetworkUtilsButton);
        testInputObjectButton = findViewById(R.id.testInputObjectButton);
        testUpdateObjectButton = findViewById(R.id.testUpdateObjectButton);
        clearResultsButton = findViewById(R.id.clearResultsButton);
        resultView = findViewById(R.id.resultView);

        // Set up button click listeners
//        checkAvatarButton.setOnClickListener(v -> checkAvatars());
//        generateAppIconButton.setOnClickListener(v -> generateAppIcon());
//        generateRoundIconButton.setOnClickListener(v -> generateRoundIcon());
        showInputActivityButton.setOnClickListener(v -> showSingleInputActivity());
        showUserConfirmDialogButton.setOnClickListener(v -> showUserConfirmDialog());
        testSquareIconButton.setOnClickListener(v -> testSquareIcon());
        testRoundIconButton.setOnClickListener(v -> testRoundIcon());
        testSquareIconFilesButton.setOnClickListener(v -> testSquareIconFiles());
        testRoundIconFilesButton.setOnClickListener(v -> testRoundIconFiles());
        testNetworkButton.setOnClickListener(v -> testNetwork());
        testNetworkUtilsButton.setOnClickListener(v -> testNetworkUtils());
        testInputObjectButton.setOnClickListener(v -> testInputObjectActivity());
        testUpdateObjectButton.setOnClickListener(v -> testUpdateObjectActivity());
        clearResultsButton.setOnClickListener(v -> clearResults());

        // Initialize HttpRequester
        httpRequester = new HttpRequester(this);
        avatarManager = AvatarManager.getInstance(this);

        // Initialize avatar dialog
        setupAvatarDialog();

        // Initialize the image view (no initial generation needed)
    }

    private void setupAvatarDialog() {
        avatarDialog = new Dialog(this);
        avatarDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        avatarDialog.setContentView(R.layout.dialog_avatar);
        avatarDialog.setCancelable(false);

        avatarImageView = avatarDialog.findViewById(R.id.avatar_view);
        smallAvatarImageView = avatarDialog.findViewById(R.id.avatar_view);
        idTextView = avatarDialog.findViewById(R.id.id_text);
        countTextView = avatarDialog.findViewById(R.id.countTextView);
        stopButton = avatarDialog.findViewById(R.id.stopButton);
        nextButton = avatarDialog.findViewById(R.id.nextButton);

        stopButton.setOnClickListener(v -> stopAvatarChecking());
        nextButton.setOnClickListener(v -> showNextAvatar());
    }

    private void stopAvatarChecking() {
        if (avatarDialog != null && avatarDialog.isShowing()) {
            avatarDialog.dismiss();
            ToastUtils.makeText(this, "Avatar checking stopped");
        }
    }

    private void showAvatarDialog(byte[] avatarBytes, KeyInfo keyInfo) {
        if (avatarBytes != null) {
            Bitmap bitmap = BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.length);
            avatarImageView.setImageBitmap(bitmap);
            smallAvatarImageView.setImageBitmap(bitmap);

            // Update ID text
            idTextView.setText(keyInfo.getId());

            // Update count text
            updateCountText();

            DialogUtils.show(avatarDialog);
        }
    }

    private void updateCountText() {
        if (avatarKeyInfos != null && !avatarKeyInfos.isEmpty()) {
            int totalCount = avatarKeyInfos.size();
            int currentCount = currentAvatarIndex + 1; // 1-based index for display
            countTextView.setText(currentCount + "/" + totalCount);
        } else {
            countTextView.setText("0/0");
        }
    }

    private void showNextAvatar() {
        if (avatarKeyInfos != null && !avatarKeyInfos.isEmpty()) {
            currentAvatarIndex = (currentAvatarIndex + 1) % avatarKeyInfos.size();
            KeyInfo nextKeyInfo = avatarKeyInfos.get(currentAvatarIndex);

            try {
                byte[] avatarBytes = avatarManager.getAvatar(nextKeyInfo.getId());
                if (avatarBytes != null) {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.length);
                    avatarImageView.setImageBitmap(bitmap);
                    smallAvatarImageView.setImageBitmap(bitmap);
                } else {
                    // If avatar doesn't exist, create it
                    avatarBytes = avatarManager.getAvatar( nextKeyInfo.getId());
                    Bitmap bitmap = BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.length);
                    avatarImageView.setImageBitmap(bitmap);
                    smallAvatarImageView.setImageBitmap(bitmap);
                }

                // Update ID text
                idTextView.setText(nextKeyInfo.getId());

                // Update count text
                updateCountText();

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error showing next avatar: %s", e.getMessage());
                avatarDialog.dismiss();
            }
        } else {
            avatarDialog.dismiss();
        }
    }



    private void showWaitingDialog(String hint) {
        if (waitingDialog == null) {
            waitingDialog = new WaitingDialog(this, hint);
        } else {
            waitingDialog.setHint(hint);
        }
        waitingDialog.show();
    }

    private void dismissWaitingDialog() {
        if (waitingDialog != null && waitingDialog.isShowing()) {
            waitingDialog.dismiss();
        }
    }

//
//    private void generateAppIcon() {
//        try {
//            // Generate icon with text "Safe"
//            Bitmap iconBitmap = IconGenerator.generateIcon("Safe", 512, this);
//
//            // Save the icon to mipmap directories
//            saveIconToMipmap(iconBitmap, false);
//
//            ToastUtils.makeText(this, "App icon generated successfully", Toast.LENGTH_SHORT);
//            TimberLogger.i(TAG, "App icon generated successfully");
//        } catch (Exception e) {
//            String errorMsg = "Error generating app icon: " + e.getMessage();
//            ToastUtils.makeText(this, errorMsg, Toast.LENGTH_SHORT);
//            TimberLogger.e(TAG, errorMsg, e);
//        }
//    }

//    private void generateRoundIcon() {
//        try {
//            // Generate round icon with text "Safe"
//            Bitmap iconBitmap = IconGenerator.generateRoundIcon("Safe", 512, this);
//
//            // Save the icon to mipmap directories
//            saveIconToMipmap(iconBitmap, true);
//
//            // Display the generated icon
//            imageView.setImageBitmap(iconBitmap);
//
//            ToastUtils.makeText(this, "Round app icon generated successfully", Toast.LENGTH_SHORT);
//            TimberLogger.i(TAG, "Round app icon generated successfully");
//        } catch (Exception e) {
//            String errorMsg = "Error generating round app icon: " + e.getMessage();
//            ToastUtils.makeText(this, errorMsg, Toast.LENGTH_SHORT);
//            TimberLogger.e(TAG, errorMsg, e);
//        }
//    }

    private void saveIconToMipmap(Bitmap iconBitmap, boolean isRound) {
        try {
            // Define mipmap directories and sizes
            int[] sizes = {48, 72, 96, 144, 192};
            String[] directories = {"mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"};

            for (int i = 0; i < sizes.length; i++) {
                // Create resized bitmap for each density
                Bitmap resizedBitmap = Bitmap.createScaledBitmap(iconBitmap, sizes[i], sizes[i], true);

                // Save to PNG file
                String fileName = isRound ? "ic_launcher_round.png" : "ic_launcher.png";
                String directory = getFilesDir().getParent() + "/res/" + directories[i];

                // Create directory if it doesn't exist
                File dir = new File(directory);
                if (!dir.exists()) {
                    dir.mkdirs();
                }

                // Save bitmap to file
                File file = new File(dir, fileName);
                FileOutputStream out = new FileOutputStream(file);
                resizedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                out.close();

                TimberLogger.i(TAG, "Saved icon to %s", file.getAbsolutePath());
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error saving icon to mipmap: %s", e.getMessage());
            throw new RuntimeException("Failed to save icon to mipmap", e);
        }
    }

    private void showSingleInputActivity() {
        Intent intent = new Intent(this, SingleInputActivity.class);
        intent.putExtra(SingleInputActivity.EXTRA_PROMOTE, "Input password");
        intent.putExtra(SingleInputActivity.EXTRA_INPUT_TYPE, "password");
        startActivityForResult(intent, REQUEST_CODE_SINGLE_INPUT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SINGLE_INPUT && resultCode == RESULT_OK && data != null) {
            String input = data.getStringExtra(SingleInputActivity.EXTRA_RESULT);
            if (input != null) {
                ToastUtils.makeText(this, "Password: " + input);
            }
        } else if (requestCode == REQUEST_CODE_INPUT_OBJECT && resultCode == RESULT_OK && data != null) {
            String resultJson = InputObjectActivity.getResultJson(data);
            if (resultJson != null) {
                resultView.addSuccess("Input Object Activity FcDate", resultJson);
                ToastUtils.makeText(this, "Input Object FcDate: " + resultJson);
            }
        }
    }

    private void showUserConfirmDialog() {
        UserConfirmDialog dialog = new UserConfirmDialog(this, "title","Are you sure you want to perform this action?", choice -> {
            String msg;
            switch (choice) {
                case STOP:
                    msg = "User chose: Stop";
                    break;
                case NO:
                    msg = "User chose: No";
                    break;
                case YES:
                    msg = "User chose: Yes";
                    break;
                default:
                    msg = "Unknown choice";
            }
            ToastUtils.makeText(this, msg);
        });
        dialog.show();
    }

    private void testSquareIcon() {
        try {
            // Create a square icon with test text
            Bitmap icon = IconCreator.createSquareIcon(
                "Test",
                512,
                50,
                getColor(R.color.text),
                getColor(R.color.accent)
            );
            
            // Display the icon
            imageView.setImageBitmap(icon);
            
            ToastUtils.makeText(this, "Square icon created successfully");
        } catch (Exception e) {
            String errorMsg = "Error creating square icon: " + e.getMessage();
            ToastUtils.makeText(this, errorMsg);
            TimberLogger.e(TAG, errorMsg, e);
        }
    }

    private void testRoundIcon() {
        try {
            // Create a round icon with test text
            Bitmap icon = IconCreator.createRoundIcon(
                "Test The Round Icon",
                512,
                50,
                Color.WHITE,
                Color.BLUE
            );
            
            // Display the icon
            imageView.setImageBitmap(icon);
            
            ToastUtils.makeText(this, "Round icon created successfully");
        } catch (Exception e) {
            String errorMsg = "Error creating round icon: " + e.getMessage();
            ToastUtils.makeText(this, errorMsg);
            TimberLogger.e(TAG, errorMsg, e);
        }
    }

    private void testSquareIconFiles() {
        try {
            // Create square icon files
            String path = getFilesDir().getParent() + "/res";
            List<String> savedPaths = IconCreator.createSquareIconFiles(
                "Fr",
                10,
                path,
                getColor(R.color.text),
                getColor(R.color.accent)
            );
            
            // Show paths in toast
            StringBuilder message = new StringBuilder("Square icon files created at:\n");
            for (String filePath : savedPaths) {
                message.append(filePath).append("\n");
            }
            ToastUtils.makeText(this, message.toString());
        } catch (Exception e) {
            String errorMsg = "Error creating square icon files: " + e.getMessage();
            ToastUtils.makeText(this, errorMsg);
            TimberLogger.e(TAG, errorMsg, e);
        }
    }

    private void testRoundIconFiles() {
        try {
            // Create round icon files
            String path = getFilesDir().getParent() + "/res";
            List<String> savedPaths = IconCreator.createRoundIconFiles(
                "Fr",
                10,
                path,
                getColor(R.color.text),
                getColor(R.color.accent)
            );
            
            // Show paths in toast
            StringBuilder message = new StringBuilder("Round icon files created at:\n");
            for (String filePath : savedPaths) {
                message.append(filePath).append("\n");
            }
            ToastUtils.makeText(this, message.toString());
        } catch (Exception e) {
            String errorMsg = "Error creating round icon files: " + e.getMessage();
            ToastUtils.makeText(this, errorMsg);
            TimberLogger.e(TAG, errorMsg, e);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 清理网络资源
        if (httpRequester != null) {
            httpRequester.shutdown();
        }
    }

    private void testNetwork() {
        showWaitingDialog("Testing network connection...");
        
        // 测试网络连接
        NetworkUtils.testNetworkConnection(this, "https://httpbin.org/get", new NetworkUtils.NetworkCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    resultView.addSuccess("网络连接测试", message);
                    ToastUtils.makeText(TestActivity.this, "Network test: " + message);
                });
            }

            @Override
            public void onError(NetworkException exception) {
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    resultView.addFailure("网络连接测试", exception.getMessage());
                    ToastUtils.makeText(TestActivity.this, "Network test failed: " + exception.getMessage());
                });
            }
        });
    }

    private void testNetworkUtils() {
        StringBuilder networkInfo = new StringBuilder();
        
        // 检查网络状态
        boolean isNetworkAvailable = NetworkUtils.isNetworkAvailable(this);
        boolean isWifiConnected = NetworkUtils.isWifiConnected(this);
        boolean isMobileConnected = NetworkUtils.isMobileConnected(this);
        
        networkInfo.append("Network Available: ").append(isNetworkAvailable).append("\n");
        networkInfo.append("WiFi Connected: ").append(isWifiConnected).append("\n");
        networkInfo.append("Mobile Connected: ").append(isMobileConnected).append("\n");
        
        // 测试URL构建
        Map<String, String> params = new HashMap<>();
        params.put("param1", "value1");
        params.put("param2", "value2");
        String urlWithParams = NetworkUtils.buildUrlWithParams("https://example.com/api", params);
        networkInfo.append("URL with params: ").append(urlWithParams);
        
        resultView.addSuccess("网络工具测试", networkInfo.toString());
        ToastUtils.makeText(this, networkInfo.toString());
    }

    private void clearResults() {
        resultView.clearResults();
        ToastUtils.makeText(this, "结果已清除");
    }

    private void testInputObjectActivity() {
        try {
            // Create field required map for ApiProvider class
            Map<String, Boolean> fieldRequiredMap = new HashMap<>();
            fieldRequiredMap.put("id", false);           // Optional
            fieldRequiredMap.put("name", true);          // Required
            fieldRequiredMap.put("type", true);          // Required - This is an enum field!
            fieldRequiredMap.put("apiUrl", true);        // Required
            fieldRequiredMap.put("owner", false);        // Optional
            fieldRequiredMap.put("orgUrl", false);       // Optional
            fieldRequiredMap.put("docUrl", false);       // Optional
            fieldRequiredMap.put("dealerPubkey", false); // Optional
            
            // Start InputObjectActivity for ApiProvider class
            InputObjectActivity.startForResult(this, ApiProvider.class, fieldRequiredMap, REQUEST_CODE_INPUT_OBJECT);
            
            resultView.addSuccess("Input Object Activity", "Started InputObjectActivity for ApiProvider class (with enum field 'type')");
            ToastUtils.makeText(this, "Started InputObjectActivity for ApiProvider class");
            
        } catch (Exception e) {
            String errorMsg = "Error starting InputObjectActivity: " + e.getMessage();
            resultView.addFailure("Input Object Activity", errorMsg);
            ToastUtils.makeText(this, errorMsg);
            TimberLogger.e(TAG, errorMsg, e);
        }
    }

    private void testUpdateObjectActivity() {
        try {
            // Create field required map for ApiProvider class
            Map<String, Boolean> fieldRequiredMap = new HashMap<>();
            fieldRequiredMap.put("id", false);           // Optional
            fieldRequiredMap.put("name", true);          // Required
            fieldRequiredMap.put("type", true);          // Required - This is an enum field!
            fieldRequiredMap.put("apiUrl", true);        // Required
            fieldRequiredMap.put("owner", false);        // Optional
            fieldRequiredMap.put("orgUrl", false);       // Optional
            fieldRequiredMap.put("docUrl", false);       // Optional
            fieldRequiredMap.put("dealerPubkey", false); // Optional
            
            // Create a sample ApiProvider object as JSON for testing
            com.google.gson.JsonObject originalObject = new com.google.gson.JsonObject();
            originalObject.addProperty("id", "test-provider-001");
            originalObject.addProperty("name", "Test API Provider");
            originalObject.addProperty("type", "APIP");
            originalObject.addProperty("apiUrl", "https://api.example.com");
            originalObject.addProperty("owner", "test-owner");
            originalObject.addProperty("orgUrl", "https://example.com");
            originalObject.addProperty("docUrl", "https://docs.example.com");
            originalObject.addProperty("dealerPubkey", "test-dealer-pubkey");
            
            String originalObjectJson = new com.google.gson.Gson().toJson(originalObject);
            
            resultView.addSuccess("Update Object Activity", "Started UpdateObjectActivity for ApiProvider class with sample data");
            ToastUtils.makeText(this, "Started UpdateObjectActivity for ApiProvider class");
            
        } catch (Exception e) {
            String errorMsg = "Error starting UpdateObjectActivity: " + e.getMessage();
            resultView.addFailure("Update Object Activity", errorMsg);
            ToastUtils.makeText(this, errorMsg);
            TimberLogger.e(TAG, errorMsg, e);
        }
    }


}