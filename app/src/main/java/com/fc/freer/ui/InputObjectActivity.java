package com.fc.freer.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.home.BaseCryptoActivity;
import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class InputObjectActivity extends BaseCryptoActivity {
    private static final String TAG = "InputObjectActivity";
    
    // Intent extras
    public static final String EXTRA_CLASS_NAME = "class_name";
    public static final String EXTRA_FIELD_REQUIRED_MAP = "field_required_map";
    public static final String EXTRA_RESULT_JSON = "result_json";
    
    private Class<?> targetClass;
    private Map<String, Boolean> fieldRequiredMap;
    private Map<String, TextInputEditText> inputFields;
    private Map<String, AutoCompleteTextView> enumFields;
    private LinearLayout inputFieldsContainer;
    private Button clearButton;
    private Button cancelButton;
    private Button confirmButton;
    
    private final Gson gson = new Gson();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Process intent data first
        String className = getIntent().getStringExtra(EXTRA_CLASS_NAME);
        @SuppressWarnings("unchecked")
        Map<String, Boolean> tempMap = (Map<String, Boolean>) getIntent().getSerializableExtra(EXTRA_FIELD_REQUIRED_MAP);
        fieldRequiredMap = tempMap != null ? new HashMap<>(tempMap) : null;
        
        if (className == null || fieldRequiredMap == null) {
            showToast(getString(R.string.invalid_configuration));
            finish();
            return;
        }

        try {
            targetClass = Class.forName(className);
            TimberLogger.d(TAG, "Successfully loaded class: " + className);
        } catch (ClassNotFoundException e) {
            TimberLogger.e(TAG, "Class not found: " + className, e);
            showToast("Class not found: " + className + "\nPlease check if the class is accessible");
            finish();
            return;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading class: " + className, e);
            showToast("Error loading class: " + className + "\n" + e.getMessage());
            finish();
            return;
        }

        // Now call parent onCreate
        super.onCreate(savedInstanceState);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_input_object;
    }

    @Override
    protected String getActivityTitle() {
        if (targetClass != null) {
            return "Input " + targetClass.getSimpleName();
        } else {
            return "Input Object";
        }
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    @Override
    protected void initializeViews() {
        inputFieldsContainer = findViewById(R.id.inputFieldsContainer);
        inputFields = new HashMap<>();
        enumFields = new HashMap<>();
        
        // Generate input fields dynamically
        generateInputFields();
    }

    @Override
    protected void setupButtons() {
        clearButton = findViewById(R.id.clearButton);
        setupButton(clearButton, v -> clearAllInputs());

        cancelButton = findViewById(R.id.cancelButton);
        setupButton(cancelButton, v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        confirmButton = findViewById(R.id.confirmButton);
        setupButton(confirmButton, v -> validateAndConfirm());
        
        // Setup click listener to hide keyboard when clicking outside input fields
        View rootView = findViewById(android.R.id.content);
        rootView.setOnClickListener(v -> hideKeyboard());
    }

    /**
     * Check if a field is an enum type
     * @param fieldName The name of the field to check
     * @return The enum class if the field is an enum, null otherwise
     */
    private Class<?> getEnumFieldType(String fieldName) {
        try {
            Field field = targetClass.getDeclaredField(fieldName);
            Class<?> fieldType = field.getType();
            if (fieldType.isEnum()) {
                return fieldType;
            }
        } catch (NoSuchFieldException e) {
            TimberLogger.d(TAG, "Field not found: " + fieldName);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error checking field type for " + fieldName, e);
        }
        return null;
    }

    /**
     * Get enum values as string array
     * @param enumClass The enum class
     * @return Array of enum value names
     */
    private String[] getEnumValues(Class<?> enumClass) {
        if (enumClass == null || !enumClass.isEnum()) {
            return new String[0];
        }
        
        Object[] enumConstants = enumClass.getEnumConstants();
        String[] values = new String[enumConstants.length];
        for (int i = 0; i < enumConstants.length; i++) {
            values[i] = enumConstants[i].toString();
        }
        return values;
    }

    /**
     * Try to get localized field names from the target class
     * @param targetClass The class to get field names from
     * @return Map of field names to localized display names, or null if not available
     */
    private Map<String, String> getLocalizedFieldNames(Class<?> targetClass) {
        try {
            // Get current locale
            Locale currentLocale = getResources().getConfiguration().getLocales().get(0);
            String language = currentLocale.getLanguage();
            
            // Try to get the appropriate method based on language
            String methodName = "zh".equals(language) ? "getFieldNamesZh" : "getFieldNamesEn";
            
            Method method = targetClass.getMethod(methodName);
            if (method != null) {
                @SuppressWarnings("unchecked")
                Map<String, String> fieldNames = (Map<String, String>) method.invoke(null);
                TimberLogger.d(TAG, "Successfully retrieved localized field names for " + targetClass.getSimpleName());
                return fieldNames;
            }
        } catch (Exception e) {
            TimberLogger.d(TAG, "Could not get localized field names for " + targetClass.getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    private void generateInputFields() {
        TimberLogger.d(TAG, "generateInputFields called, fieldRequiredMap: " + (fieldRequiredMap != null ? "not null" : "null"));
        
        if (fieldRequiredMap == null) {
            TimberLogger.w(TAG, "fieldRequiredMap is null, skipping field generation");
            return;
        }
        
        // Try to get localized field names
        Map<String, String> localizedFieldNames = getLocalizedFieldNames(targetClass);
        
        TimberLogger.d(TAG, "Generating " + fieldRequiredMap.size() + " input fields");
        LayoutInflater inflater = LayoutInflater.from(this);
        
        for (Map.Entry<String, Boolean> entry : fieldRequiredMap.entrySet()) {
            String fieldName = entry.getKey();
            boolean isRequired = entry.getValue();
            
            TimberLogger.d(TAG, "Creating field: " + fieldName + " (required: " + isRequired + ")");
            
            // Check if this field is an enum type
            Class<?> enumType = getEnumFieldType(fieldName);
            
            View fieldView;
            if (enumType != null) {
                // Create enum dropdown field
                fieldView = inflater.inflate(R.layout.layout_enum_field, inputFieldsContainer, false);
                TimberLogger.d(TAG, "Field " + fieldName + " is enum type: " + enumType.getSimpleName());
            } else {
                // Create regular text input field
                fieldView = inflater.inflate(R.layout.layout_input_field, inputFieldsContainer, false);
            }
            
            // Set field label with localized name if available
            TextView fieldLabel = fieldView.findViewById(R.id.fieldLabel);
            String labelText = fieldName; // Default to original field name
            
            if (localizedFieldNames != null && localizedFieldNames.containsKey(fieldName)) {
                labelText = localizedFieldNames.get(fieldName);
                TimberLogger.d(TAG, "Using localized field name: " + fieldName + " -> " + labelText);
            } else {
                TimberLogger.d(TAG, "Using original field name: " + fieldName);
            }
            
            if (isRequired) {
                labelText += " *";
            }
            fieldLabel.setText(labelText);
            
            if (enumType != null) {
                // Setup enum dropdown
                AutoCompleteTextView enumDropdown = fieldView.findViewById(R.id.enumDropdown);
                String[] enumValues = getEnumValues(enumType);
                
                ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this, 
                    android.R.layout.simple_dropdown_item_1line, 
                    enumValues
                );
                enumDropdown.setAdapter(adapter);
                
                // Store reference to enum field
                enumFields.put(fieldName, enumDropdown);
                
                TimberLogger.d(TAG, "Created enum dropdown for " + fieldName + " with " + enumValues.length + " values");
            } else {
                // Setup regular text input
                TextInputEditText fieldInput = fieldView.findViewById(R.id.fieldInput);
                fieldInput.setHint(""); // 不显示提示词
                
                // Store reference to input field
                inputFields.put(fieldName, fieldInput);
            }
            
            // Add to container
            inputFieldsContainer.addView(fieldView);
        }
        
        TimberLogger.d(TAG, "Generated " + inputFields.size() + " text input fields and " + enumFields.size() + " enum dropdown fields");
    }

    private void clearAllInputs() {
        if (inputFields != null) {
            for (TextInputEditText input : inputFields.values()) {
                input.setText("");
            }
        }
        
        if (enumFields != null) {
            for (AutoCompleteTextView dropdown : enumFields.values()) {
                dropdown.setText("");
            }
        }
    }

    private void validateAndConfirm() {
        if (fieldRequiredMap == null) {
            showToast(getString(R.string.invalid_configuration));
            return;
        }
        
        JsonObject jsonObject = new JsonObject();
        boolean hasErrors = false;
        
        // Validate and collect input values for text fields
        for (Map.Entry<String, TextInputEditText> entry : inputFields.entrySet()) {
            String fieldName = entry.getKey();
            TextInputEditText input = entry.getValue();
            String value = input.getText() != null ? input.getText().toString().trim() : "";
            
            // Check if required field is empty
            if (fieldRequiredMap.get(fieldName) && TextUtils.isEmpty(value)) {
                input.setError(getString(R.string.this_field_is_required));
                hasErrors = true;
            } else {
                input.setError(null);
                // Add to JSON object
                if (!TextUtils.isEmpty(value)) {
                    jsonObject.addProperty(fieldName, value);
                }
            }
        }
        
        // Validate and collect input values for enum fields
        for (Map.Entry<String, AutoCompleteTextView> entry : enumFields.entrySet()) {
            String fieldName = entry.getKey();
            AutoCompleteTextView dropdown = entry.getValue();
            String value = dropdown.getText() != null ? dropdown.getText().toString().trim() : "";
            
            // Check if required field is empty
            if (fieldRequiredMap.get(fieldName) && TextUtils.isEmpty(value)) {
                dropdown.setError(getString(R.string.this_field_is_required));
                hasErrors = true;
            } else {
                dropdown.setError(null);
                // Add to JSON object
                if (!TextUtils.isEmpty(value)) {
                    jsonObject.addProperty(fieldName, value);
                }
            }
        }
        
        if (hasErrors) {
            showToast(getString(R.string.please_fill_in_all_required_fields));
            return;
        }
        
        // Return the JSON result
        String resultJson = gson.toJson(jsonObject);
        Intent resultIntent = new Intent();
        resultIntent.putExtra(EXTRA_RESULT_JSON, resultJson);
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    /**
     * Static method to start the activity
     * @param context The context to start the activity from
     * @param targetClass The class of the object to input
     * @param fieldRequiredMap Map of field names to required status
     * @param requestCode The request code for the activity result
     */
    public static void startForResult(@NonNull android.content.Context context, 
                                    @NonNull Class<?> targetClass, 
                                    @NonNull Map<String, Boolean> fieldRequiredMap, 
                                    int requestCode) {
        Intent intent = new Intent(context, InputObjectActivity.class);
        intent.putExtra(EXTRA_CLASS_NAME, targetClass.getName());
        intent.putExtra(EXTRA_FIELD_REQUIRED_MAP, (java.io.Serializable) fieldRequiredMap);
        
        if (context instanceof android.app.Activity) {
            ((android.app.Activity) context).startActivityForResult(intent, requestCode);
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    /**
     * Helper method to get the result JSON from the activity result
     * @param data The intent data from the activity result
     * @return The JSON string, or null if not found
     */
    public static String getResultJson(Intent data) {
        if (data != null) {
            return data.getStringExtra(EXTRA_RESULT_JSON);
        }
        return null;
    }
} 