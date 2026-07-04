package com.fc.freer.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.data.feipData.App;
import com.fc.fc_ajdk.data.feipData.Code;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.data.feipData.Protocol;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.Team;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.home.RateActivity;
import com.fc.freer.home.RateFreerActivity;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.HatManager;
import com.fc.freer.data.DataSyncManager;
import com.fc.fc_ajdk.data.fcData.Hat;
import com.fc.freer.data.HatDetailActivity;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.utils.ApiCenter;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.CryptoDataByte;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.fc.fc_ajdk.data.fcData.FcEntity;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.AvatarManager;
import com.fc.fc_ajdk.core.crypto.KeyTools;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DetailFragment extends Fragment {
    public final static String TAG = "DetailFragment";
    
    // Constants
    private static final int INDENT = 20; // Indent for nested elements
    private static final int AVATAR_SIZE = 100; // Size of the avatar in dp
    private static final int MAX_NESTED_DEPTH = 5; // Maximum depth for nested objects
    private static final int MAX_COLLECTION_PREVIEW = 3; // Maximum items to show in collapsed collection
    
    // Instance variables
    private float density;
    private FcEntity currentEntity;
    private Class<? extends FcEntity> currentEntityClass;

    // Add field for satoshi field list
    private List<String> satoshiFieldList;
    
    // Add field for timestamp field list
    private List<String> timestampFieldList;
    
    // Track expanded/collapsed state for complex objects
    private Map<String, Boolean> expandedStates = new HashMap<>();
    
    // UI components
    private ImageView avatarView;
    private LinearLayout detailContainer;

    public static DetailFragment newInstance(FcEntity entity, Class<?> entityClass) {
        TimberLogger.d(TAG, "DetailFragment newInstance: Creating new fragment with entity ID = " + 
            (entity != null ? entity.getId() : "null") + ", entityClass = " + 
            (entityClass != null ? entityClass.getSimpleName() : "null"));
        DetailFragment fragment = new DetailFragment();
        Bundle args = new Bundle();
        args.putString(DetailActivity.EXTRA_ENTITY_JSON, entity != null ? entity.toJson() : null);
        args.putString(DetailActivity.EXTRA_ENTITY_CLASS, entityClass != null ? entityClass.getName() : null);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TimberLogger.d(TAG, "DetailFragment onCreate: Starting fragment creation");

        // Get entity and class from arguments
        Bundle args = getArguments();
        if (args != null) {
            String entityJson = args.getString(DetailActivity.EXTRA_ENTITY_JSON);
            String className = args.getString(DetailActivity.EXTRA_ENTITY_CLASS);
            try {
                if (className != null) {
                    currentEntityClass = (Class<? extends FcEntity>) Class.forName(className);
                }
                if (entityJson != null && currentEntityClass != null) {
                    currentEntity = FcEntity.fromJson(entityJson, currentEntityClass);
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "DetailFragment onCreate: Error loading entity: " + e.getMessage());
            }
        }

        if (currentEntity == null || currentEntityClass == null) {
            TimberLogger.e(TAG, "DetailFragment onCreate: No entity data available. Entity: " + 
                (currentEntity != null ? "not null" : "null") + ", EntityClass: " + 
                (currentEntityClass != null ? "not null" : "null"));
            return;
        }

        // Get display density
        density = getResources().getDisplayMetrics().density;
        TimberLogger.d(TAG, "DetailFragment onCreate: Display density = " + density);


        // Get satoshi field list from entity class
        try {
            Method getSatoshiFieldListMethod = currentEntityClass.getMethod(FcEntity.METHOD_GET_SATOSHI_FIELD_LIST);
            satoshiFieldList = (List<String>) getSatoshiFieldListMethod.invoke(null);
            TimberLogger.d(TAG, "DetailFragment onCreate: satoshiFieldList size = " + (satoshiFieldList != null ? satoshiFieldList.size() : 0));
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to get satoshi field list: %s", e.getMessage());
            satoshiFieldList = new ArrayList<>();
        }
        
        // Get timestamp field list from entity class
        try {
            Method getTimestampFieldListMethod = currentEntityClass.getMethod(FcEntity.METHOD_GET_TIMESTAMP_FIELD_LIST);
            timestampFieldList = (List<String>) getTimestampFieldListMethod.invoke(null);
            TimberLogger.d(TAG, "DetailFragment onCreate: timestampFieldList size = " + (timestampFieldList != null ? timestampFieldList.size() : 0));
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to get timestamp field list: %s", e.getMessage());
            timestampFieldList = new ArrayList<>();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        TimberLogger.d(TAG, "DetailFragment onCreateView: Starting view creation");
        View view = inflater.inflate(R.layout.fragment_detail, container, false);
        
        detailContainer = view.findViewById(R.id.detail_container);
        if (detailContainer == null) {
            TimberLogger.e(TAG, "DetailFragment onCreateView: detail_container not found in layout");
        } else {
            TimberLogger.d(TAG, "DetailFragment onCreateView: detail_container found");
        }
        
        avatarView = view.findViewById(R.id.avatar_view);
        if (avatarView == null) {
            TimberLogger.e(TAG, "DetailFragment onCreateView: avatar_view not found in layout");
        } else {
            TimberLogger.d(TAG, "DetailFragment onCreateView: avatar_view found");
        }
        
        // Setup Rate button
        View btnRate = view.findViewById(R.id.btn_rate);
        if (btnRate != null && currentEntity != null && currentEntityClass != null) {
            if (isRateableEntityType(currentEntityClass)) {
                btnRate.setVisibility(View.VISIBLE);
                btnRate.setOnClickListener(v -> openRateActivity());
            }
        }

        // Setup Make QR and Copy buttons for entity JSON
        View btnMakeQr = view.findViewById(R.id.btn_make_qr);
        View btnCopyJson = view.findViewById(R.id.btn_copy_json);
        View btnBack = view.findViewById(R.id.back_button);

        if (btnMakeQr != null && btnCopyJson != null && currentEntity != null) {
            btnMakeQr.setOnClickListener(v -> {
                String json = currentEntity.toNiceJson();
                com.fc.freer.utils.QRCodeGenerator.generateAndShowQRCode(requireContext(), json);
            });
            btnCopyJson.setOnClickListener(v -> {
                String json = currentEntity.toNiceJson();
                ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("entity_json", json);
                clipboard.setPrimaryClip(clip);
                ToastUtils.makeText(requireContext(), getString(R.string.copied));
            });
        }

        // Set click listener for Back button
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> {
                requireActivity().finish();
            });
        }
        
        setupView();
        TimberLogger.d(TAG, "DetailFragment onCreateView: View setup completed");
        return view;
    }

    private void setupView() {
        TimberLogger.i(TAG, "Setting up view");
        
        // Check if entity is null
        if (currentEntity == null) {
            TimberLogger.e(TAG, "Entity is null");
            
            // Create a simple view with a message
            TextView messageView = new TextView(requireContext());
            messageView.setText("No data available");
            messageView.setTextSize(18);
            messageView.setGravity(Gravity.CENTER);
            
            // Remove existing views from detail container
            detailContainer.removeAllViews();
            detailContainer.addView(messageView);
            return;
        }
        
        // Check if avatar should be shown
        boolean showAvatar = true;
        String id = currentEntity.getId();
        if (id == null || !KeyTools.isGoodFid(id)) {
            showAvatar = false;
        }
        
        // Load the avatar if allowed
        if (showAvatar) {
            loadAvatar();
        } else {
            avatarView.setVisibility(View.GONE);
        }
        
        // Create a container for the ID field
        LinearLayout idContainer = new LinearLayout(requireContext());
        idContainer.setOrientation(LinearLayout.VERTICAL);
        
        // Set layout parameters for the ID container
        LinearLayout.LayoutParams idContainerParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        idContainer.setLayoutParams(idContainerParams);
        
        // Add the ID field at the first row with bold if ID is not null
        if (currentEntity.getId() != null) {
            LinearLayout idRow = createDetailRow("ID", currentEntity.getId(), true);
            idContainer.addView(idRow);
        }
        
        // Create a container for the avatar and ID
        LinearLayout avatarIdContainer = new LinearLayout(requireContext());
        avatarIdContainer.setOrientation(LinearLayout.HORIZONTAL);
        
        // Set layout parameters for the avatar ID container
        LinearLayout.LayoutParams avatarIdParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        avatarIdParams.setMargins(0, 0, 0, (int) (16 * density));
        avatarIdContainer.setLayoutParams(avatarIdParams);
        
        // Remove the avatar view from its current parent
        ViewGroup avatarParent = (ViewGroup) avatarView.getParent();
        if (avatarParent != null) {
            avatarParent.removeView(avatarView);
        }
        
        // Add the avatar and ID container to the avatar ID container
        if (showAvatar) {
            avatarView.setVisibility(View.VISIBLE);
            avatarIdContainer.addView(avatarView);
        }
        avatarIdContainer.addView(idContainer);
        
        // Remove existing views from detail container
        detailContainer.removeAllViews();
        
        // Add the avatar ID container to the detail container
        detailContainer.addView(avatarIdContainer);
        
        // Add other fields
        addEntityFields(currentEntity);
    }

    private void loadAvatar() {
        if (currentEntity != null && currentEntity.getId() != null) {
            try {
                // Generate avatar using AvatarManager (which handles caching automatically)
                AvatarManager avatarManager = AvatarManager.getInstance(getContext());
                byte[] avatarBytes = avatarManager.getAvatar(currentEntity.getId());
                
                // Set the avatar image
                if (avatarBytes != null) {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.length);
                    if (bitmap != null) {
                        avatarView.setImageBitmap(bitmap);
                        avatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        // Add click listener to show avatar dialog
                        avatarView.setOnClickListener(v -> {
                            if (currentEntity != null && currentEntity.getId() != null) {
                                AvatarManager.showAvatarDialog(requireContext(), currentEntity.getId());
                            }
                        });
                        return;
                    }
                }
                
                // If everything fails, set a default gray background
                avatarView.setBackgroundColor(Color.LTGRAY);
                
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error setting avatar: %s", e.getMessage());
                avatarView.setBackgroundColor(Color.LTGRAY);
            }
        }
    }

    private void addEntityFields(FcEntity entity) {
        // Try to get ordered field names from the entity class
        LinkedHashMap<String, Map<String, String>> fieldNameMap = getOrderedFieldMap(entity.getClass());
        
        if (fieldNameMap != null && !fieldNameMap.isEmpty()) {
            // Use the ordered field map from the class
            addFieldsFromOrderedMap(entity, fieldNameMap);
        } else {
            // Fallback to reflection-based approach for classes without getFieldNameMap()
            addFieldsUsingReflection(entity);
        }
    }
    
    private LinkedHashMap<String, Map<String, String>> getOrderedFieldMap(Class<?> entityClass) {
        try {
            // Try to call getFieldNameMap() method on the entity class
            Method getFieldNameMapMethod = entityClass.getMethod("getFieldNameMap");
            return (LinkedHashMap<String, Map<String, String>>) getFieldNameMapMethod.invoke(null);
        } catch (Exception e) {
            // Method doesn't exist or failed to invoke, return null to use fallback
            TimberLogger.d(TAG, "getFieldNameMap() method not found in %s, using reflection fallback", entityClass.getSimpleName());
            return null;
        }
    }
    
    private void addFieldsFromOrderedMap(FcEntity entity, LinkedHashMap<String, Map<String, String>> fieldNameMap) {
        // Get current locale language code
        String languageCode = getResources().getConfiguration().getLocales().get(0).getLanguage();

        for (Map.Entry<String, Map<String, String>> entry : fieldNameMap.entrySet()) {
            String fieldName = entry.getKey();
            Map<String, String> languageMap = entry.getValue();

            // Skip the "id" field since it's already displayed at the top
            if ("id".equals(fieldName)) {
                continue;
            }

            try {
                Object value = getFieldValue(entity, fieldName);

                // Skip fields with null values
                if (value == null) {
                    continue;
                }

                // Get display name based on current locale (prefer current language, fallback to English, then field name)
                String displayName = languageMap.get(languageCode);
                if (displayName == null || displayName.isEmpty()) {
                    displayName = languageMap.getOrDefault("en", fieldName);
                }
                if (displayName == null || displayName.isEmpty()) {
                    displayName = fieldName;
                }

                // Create the field row with proper handling for complex objects
                createFieldRowWithComplexHandling(displayName, value, fieldName, 0);

            } catch (Exception e) {
                TimberLogger.e(TAG, "Failed to get field value for %s: %s", fieldName, e.getMessage());
            }
        }
    }
    
    private void addFieldsUsingReflection(FcEntity entity) {
        // Build complete class hierarchy from most specific to most general
        List<Class<?>> classHierarchy = new ArrayList<>();
        Class<?> currentClass = entity.getClass();
        
        // Collect all classes in the hierarchy up to and including FcEntity
        while (currentClass != null && currentClass != Object.class) {
            classHierarchy.add(currentClass);
            // Stop after FcEntity (don't go to Object)
            if (currentClass == FcEntity.class) {
                break;
            }
            currentClass = currentClass.getSuperclass();
        }
        
        // Collect all fields from the entire class hierarchy
        List<Field> allFields = new ArrayList<>();
        for (Class<?> clazz : classHierarchy) {
            Field[] fields = clazz.getDeclaredFields();
            allFields.addAll(Arrays.asList(fields));
        }
        
        // Process all fields
        for (Field field : allFields) {
            field.setAccessible(true);
            String fieldName = field.getName();
            
            // Skip static, final, or transient fields
            int modifiers = field.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(modifiers) || 
                java.lang.reflect.Modifier.isTransient(modifiers)) {
                continue;
            }
            
            // Skip the "id" field since it's already displayed at the top
            if ("id".equals(fieldName)) {
                continue;
            }
            
            try {
                Object value = field.get(entity);
                
                // Skip fields with null values
                if (value == null) {
                    continue;
                }
                
                String displayValue = value.toString();
                
                // Format satoshi values
                if (satoshiFieldList != null && satoshiFieldList.contains(fieldName) && value instanceof Number) {
                    try {
                        long satoshiValue = ((Number) value).longValue();
                        displayValue = com.fc.fc_ajdk.utils.FchUtils.formatSatoshiToCoin(satoshiValue);
                    } catch (Exception e) {
                        TimberLogger.e(TAG, "Failed to format satoshi value for field %s: %s", fieldName, e.getMessage());
                    }
                }
                
                // Format timestamp values
                if (timestampFieldList != null && timestampFieldList.contains(fieldName) && value instanceof Number) {
                    try {
                        long timestampValue = ((Number) value).longValue();
                        displayValue = com.fc.fc_ajdk.utils.DateUtils.longShortToTime(timestampValue, com.fc.fc_ajdk.utils.DateUtils.LONG_FORMAT);
                    } catch (Exception e) {
                        TimberLogger.e(TAG, "Failed to format timestamp value for field %s: %s", fieldName, e.getMessage());
                    }
                }
                
                // Create the field row with original field name and complex object handling
                createFieldRowWithComplexHandling(fieldName, value, fieldName, 0);
                
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }
    }
    
    private Object getFieldValue(FcEntity entity, String fieldName) {
        try {
            // Try standard getter naming convention first
            String getterName = "get" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
            try {
                Method getter = entity.getClass().getMethod(getterName);
                return getter.invoke(entity);
            } catch (NoSuchMethodException e) {
                // If standard getter not found, try isXxx for boolean fields
                if (fieldName.startsWith("is")) {
                    // Already in isXxx format
                    Method getter = entity.getClass().getMethod(fieldName);
                    return getter.invoke(entity);
                } else {
                    // Try isXxx format for boolean fields
                    getterName = "is" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
                    Method getter = entity.getClass().getMethod(getterName);
                    return getter.invoke(entity);
                }
            }
        } catch (Exception e) {
            // Try direct field access as a fallback
            try {
                Field field = entity.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(entity);
            } catch (Exception ex) {
                TimberLogger.e(TAG, "Failed to get field value for %s: %s", fieldName, ex.getMessage());
                return null;
            }
        }
    }
    
    private void createFieldRow(String displayName, String displayValue, String fieldName) {
        createFieldRow(displayName, displayValue, fieldName, null, 0);
    }
    
    private void createFieldRow(String displayName, String displayValue, String fieldName, Object originalValue, int indentLevel) {
        // Create a row for each field
        LinearLayout row = new LinearLayout(requireContext());
        row.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        row.setOrientation(LinearLayout.HORIZONTAL);
        
        // Apply indentation for nested objects
        int leftPadding = (int) (INDENT * indentLevel * density);
        row.setPadding(leftPadding, 8, 0, 8);

        // Check if this is a complex object that can be expanded/collapsed
        boolean isComplexObject = originalValue != null && (originalValue instanceof Collection || 
                                originalValue instanceof Map || originalValue instanceof FcEntity);
        boolean isExpanded = expandedStates.getOrDefault(fieldName + "_" + indentLevel, false);
        
        // Add expand/collapse icon for complex objects
        if (isComplexObject) {
            ImageView expandIcon = new ImageView(requireContext());
            int iconResource = isExpanded ? android.R.drawable.arrow_down_float : android.R.drawable.arrow_up_float;
            expandIcon.setImageResource(iconResource);
            int iconSize = (int) (20 * density);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
            iconParams.setMargins(0, 0, (int)(8 * density), 0);
            expandIcon.setLayoutParams(iconParams);
            expandIcon.setClickable(true);
            expandIcon.setFocusable(true);
            
            String expandKey = fieldName + "_" + indentLevel;
            expandIcon.setOnClickListener(v -> {
                boolean newExpanded = !expandedStates.getOrDefault(expandKey, false);
                expandedStates.put(expandKey, newExpanded);
                refreshComplexObjectDisplay(displayName, originalValue, fieldName, indentLevel, newExpanded);
            });
            
            row.addView(expandIcon);
        }

        // Add field name with colon
        TextView nameView = new TextView(requireContext());
        nameView.setText(displayName + ": ");
        nameView.setTextSize(14 + (indentLevel == 0 ? 2 : 0)); // Slightly larger for top level
        nameView.setTypeface(null, Typeface.BOLD);
        nameView.setTextColor(ContextCompat.getColor(requireContext(), R.color.field_name));
        nameView.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        // Add field value
        TextView valueView = new TextView(requireContext());
        valueView.setText(displayValue);
        valueView.setTextSize(14 + (indentLevel == 0 ? 2 : 0));
        valueView.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        
        // Set different text color for nested items
        if (indentLevel > 0) {
            valueView.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray));
        }
        
        // Make value copyable when clicked
        valueView.setOnClickListener(v -> {
            String text = valueView.getText().toString();
            if (!text.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Copied text", text);
                clipboard.setPrimaryClip(clip);
                ToastUtils.makeText(requireContext(), getString(R.string.copied));
            }
        });

        // Check if field value is a good FID and add person icon
        boolean isGoodFid = displayValue != null && KeyTools.isGoodFid(displayValue);

        // Check if this is a cipher field
        boolean isCipherField = fieldName.toLowerCase().contains("cipher");

        // Add views to row
        row.addView(nameView);
        row.addView(valueView);

        // Add person icon for FID fields
        if (isGoodFid) {
            ImageView personIcon = new ImageView(requireContext());
            personIcon.setImageResource(R.drawable.ic_person);
            personIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.accent));
            int iconSize = (int) (24 * density);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                iconSize, iconSize);
            iconParams.setMargins((int)(8 * density), 0, 0, 0);
            personIcon.setLayoutParams(iconParams);
            personIcon.setClickable(true);
            personIcon.setFocusable(true);
            personIcon.setContentDescription("View FID info");

            String finalFidValue = displayValue;
            personIcon.setOnClickListener(v -> {
                fetchAndShowCidInfo(finalFidValue);
            });

            row.addView(personIcon);

            // Add list icon for adding FID to global list
            ImageView listIcon = new ImageView(requireContext());
            listIcon.setImageResource(R.drawable.ic_list);
            listIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.accent));
            LinearLayout.LayoutParams listIconParams = new LinearLayout.LayoutParams(
                iconSize, iconSize);
            listIconParams.setMargins((int)(8 * density), 0, 0, 0);
            listIcon.setLayoutParams(listIconParams);
            listIcon.setClickable(true);
            listIcon.setFocusable(true);
            listIcon.setContentDescription("Add FID to list");

            listIcon.setOnClickListener(v -> {
                com.fc.freer.FreerApplication.addFid(finalFidValue);
                ToastUtils.makeText(requireContext(), getString(R.string.toast_fid_added_to_list));
            });

            row.addView(listIcon);
        }

        // Add decrypt/QR icon for cipher fields
        if (isCipherField) {
            ImageView cipherIcon = new ImageView(requireContext());
            int iconSize = (int) (24 * density);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                iconSize, iconSize);
            iconParams.setMargins((int)(8 * density), 0, 0, 0);
            cipherIcon.setLayoutParams(iconParams);
            cipherIcon.setClickable(true);
            cipherIcon.setFocusable(true);

            // Check if live FID has private key cipher
            FidManager fidManager = FidManager.getInstance();
            boolean hasPrikeyCipher = fidManager != null &&
                fidManager.getLiveKeyInfo() != null &&
                fidManager.getLiveKeyInfo().getPrikeyCipher() != null;

            String finalDisplayValue = displayValue;

            if (hasPrikeyCipher) {
                // Case 1: Can decrypt - show decrypt icon
                cipherIcon.setImageResource(R.drawable.ic_decrypt);
                cipherIcon.setContentDescription(getString(R.string.decrypt));

                cipherIcon.setOnClickListener(v -> {
                    try {
                        byte[] decryptedContent = decryptCipher(finalDisplayValue);
                        if (decryptedContent != null) {
                            // Show decrypted content in a result dialog
                            if(fieldName.toLowerCase().contains("prikey")){
                                String prikeyBase58 = KeyTools.prikey32To38WifCompressed(Hex.toHex(decryptedContent));
                                if(prikeyBase58!=null && !prikeyBase58.isEmpty())
                                    decryptedContent = prikeyBase58.getBytes();
                            }
                            com.fc.freer.utils.ResultDialog.show(requireContext(), getString(R.string.decrypted), decryptedContent, null);
                        } else {
                            ToastUtils.makeText(requireContext(), getString(R.string.failed_to_decrypt));
                        }
                    } catch (Exception e) {
                        ToastUtils.makeText(requireContext(), getString(R.string.failed_to_decrypt) + ": " + e.getMessage());
                    }
                });
            } else {
                // Case 2: Cannot decrypt - show QR make icon
                cipherIcon.setImageResource(R.drawable.ic_make_qr);
                cipherIcon.setContentDescription("Make QR Code");

                cipherIcon.setOnClickListener(v -> {
                    if (finalDisplayValue != null && !finalDisplayValue.isEmpty()) {
                        com.fc.freer.utils.QRCodeGenerator.generateAndShowQRCode(requireContext(), finalDisplayValue);
                    } else {
                        ToastUtils.makeText(requireContext(), getString(R.string.toast_no_data_create_qr));
                    }
                });
            }

            row.addView(cipherIcon);
        }

        // Add download icon for DID fields. A DID references a content-addressed document
        // stored on DISK; tapping the icon fetches it and opens the resulting HAT.
        if (isDidField(fieldName, displayValue)) {
            ImageView downloadIcon = new ImageView(requireContext());
            downloadIcon.setImageResource(R.drawable.ic_download);
            downloadIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.accent));
            int iconSize = (int) (24 * density);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                iconSize, iconSize);
            iconParams.setMargins((int)(8 * density), 0, 0, 0);
            downloadIcon.setLayoutParams(iconParams);
            downloadIcon.setClickable(true);
            downloadIcon.setFocusable(true);
            downloadIcon.setContentDescription(getString(R.string.download));

            String finalDid = displayValue;
            downloadIcon.setOnClickListener(v -> downloadDidDocument(finalDid));

            row.addView(downloadIcon);
        }

        // Add row to container
        detailContainer.addView(row);
        
        // If this is a complex object and it's expanded, add nested content
        if (isComplexObject && isExpanded) {
            addNestedContent(originalValue, fieldName, indentLevel + 1);
        }
    }

    private LinearLayout createDetailRow(String label, String value, boolean isBold) {
        // Create row container
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        
        // Set layout parameters for the row
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, 0, 0, (int) (8 * density));
        row.setLayoutParams(rowParams);
        
        // Create label view
        TextView labelView = new TextView(requireContext());
        labelView.setText(label + ": ");
        labelView.setTextSize(16);
        // Always make the label (field name) bold
        labelView.setTypeface(null, Typeface.BOLD);
        // Set the color of the field name to the 'field_name' color resource
        labelView.setTextColor(ContextCompat.getColor(requireContext(), R.color.field_name));
        
        // Set layout parameters for the label view
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelView.setLayoutParams(labelParams);
        
        // Create value view
        TextView valueView = new TextView(requireContext());
        valueView.setText(value);
        valueView.setTextSize(16);
        valueView.setTypeface(null, isBold ? Typeface.BOLD : Typeface.NORMAL);
        
        // Set layout parameters for the value view
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        valueView.setLayoutParams(valueParams);
        
        // Set up click listener to copy text to clipboard
        valueView.setOnClickListener(v -> {
            String text = valueView.getText().toString();
            if (!text.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Copied text", text);
                clipboard.setPrimaryClip(clip);
                ToastUtils.makeText(requireContext(), getString(R.string.copied));
            }
        });
        
        // Add views to row
        row.addView(labelView);
        row.addView(valueView);
        
        return row;
    }

    private void createFieldRowWithComplexHandling(String displayName, Object value, String fieldName, int indentLevel) {
        if (value == null) {
            createFieldRow(displayName, "null", fieldName, null, indentLevel);
            return;
        }
        
        // Check if this exceeds maximum nested depth
        if (indentLevel >= MAX_NESTED_DEPTH) {
            createFieldRow(displayName, "[Max depth reached]", fieldName, null, indentLevel);
            return;
        }
        
        String displayValue;
        Object originalValue = value;
        
        if (value instanceof Collection<?> collection) {
            if (collection.isEmpty()) {
                originalValue = null;
            }
            displayValue = "["+ collection.size() +"]";//getCollectionPreview(collection);
        } else if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.isEmpty()) {
                originalValue = null;
            }
            displayValue = "["+ map.size() +"]";

        } else if (value instanceof FcEntity) {
            displayValue = "";//entity.getClass().getSimpleName() + " {" + (entity.getId() != null ? "id: " + entity.getId() : "...") + "}";
        } else if (value.getClass().isArray()) {
            // Handle arrays - convert array to list for display
            displayValue = formatArrayValue(value);
            originalValue = null; // Don't treat as expandable complex object for now
        } else {
            // Handle primitive types and other objects
            displayValue = formatSimpleValue(value, fieldName);
            originalValue = null; // Don't treat as complex object
        }
        
        createFieldRow(displayName, displayValue, fieldName, originalValue, indentLevel);
    }
    
    /**
     * Format array values for display
     */
    private String formatArrayValue(Object array) {
        if (array instanceof Object[]) {
            return Arrays.toString((Object[]) array);
        } else if (array instanceof int[]) {
            return Arrays.toString((int[]) array);
        } else if (array instanceof long[]) {
            return Arrays.toString((long[]) array);
        } else if (array instanceof double[]) {
            return Arrays.toString((double[]) array);
        } else if (array instanceof float[]) {
            return Arrays.toString((float[]) array);
        } else if (array instanceof boolean[]) {
            return Arrays.toString((boolean[]) array);
        } else if (array instanceof byte[]) {
            return Arrays.toString((byte[]) array);
        } else if (array instanceof short[]) {
            return Arrays.toString((short[]) array);
        } else if (array instanceof char[]) {
            return Arrays.toString((char[]) array);
        }
        // Fallback for unknown array types
        return array.toString();
    }
    
//    private String getCollectionPreview(Collection<?> collection) {
//        StringBuilder preview = new StringBuilder();
//        preview.append("[");
//
//        int count = 0;
//        for (Object item : collection) {
//            if (count > 0) preview.append(", ");
//            if (count >= MAX_COLLECTION_PREVIEW) {
//                preview.append("... (").append(collection.size()).append(" items)");
//                break;
//            }
//
//            if (item == null) {
//                preview.append("null");
//            } else if (item instanceof String) {
//                String str = item.toString();
//                if (str.length() > 20) {
//                    preview.append("\"").append(str.substring(0, 17)).append("...\"");
//                } else {
//                    preview.append("\"").append(str).append("\"");
//                }
//            } else if (item instanceof Number || item instanceof Boolean) {
//                preview.append(item.toString());
//            } else if (item instanceof FcEntity) {
//                FcEntity entity = (FcEntity) item;
//                preview.append(entity.getClass().getSimpleName());
//                if (entity.getId() != null) {
//                    preview.append("(").append(entity.getId()).append(")");
//                }
//            } else {
//                preview.append(item.getClass().getSimpleName());
//            }
//            count++;
//        }
//
//        if (count <= MAX_COLLECTION_PREVIEW && collection.size() > MAX_COLLECTION_PREVIEW) {
//            preview.append("]");
//        } else if (count <= MAX_COLLECTION_PREVIEW) {
//            preview.append("]");
//        }
//
//        return preview.toString();
//    }
    
//    private String getMapPreview(Map<?, ?> map) {
//        StringBuilder preview = new StringBuilder();
//        preview.append("{");
//
//        int count = 0;
//        for (Map.Entry<?, ?> entry : map.entrySet()) {
//            if (count > 0) preview.append(", ");
//            if (count >= MAX_COLLECTION_PREVIEW) {
//                preview.append("... (").append(map.size()).append(" entries)");
//                break;
//            }
//
//            Object key = entry.getKey();
//            Object value = entry.getValue();
//
//            // Format key
//            String keyStr = key != null ? key.toString() : "null";
//            if (keyStr.length() > 15) {
//                keyStr = keyStr.substring(0, 12) + "...";
//            }
//
//            // Format value preview
//            String valueStr;
//            if (value == null) {
//                valueStr = "null";
//            } else if (value instanceof String) {
//                String str = value.toString();
//                valueStr = str.length() > 15 ? "\"" + str.substring(0, 12) + "...\"" : "\"" + str + "\"";
//            } else if (value instanceof Number || value instanceof Boolean) {
//                valueStr = value.toString();
//            } else if (value instanceof Collection) {
//                valueStr = "[" + ((Collection<?>) value).size() + " items]";
//            } else if (value instanceof Map) {
//                valueStr = "{" + ((Map<?, ?>) value).size() + " entries}";
//            } else if (value instanceof FcEntity) {
//                valueStr = ((FcEntity) value).getClass().getSimpleName();
//            } else {
//                valueStr = value.getClass().getSimpleName();
//            }
//
//            preview.append(keyStr).append(": ").append(valueStr);
//            count++;
//        }
//
//        preview.append("}");
//        return preview.toString();
//    }
    
    private String formatSimpleValue(Object value, String fieldName) {
        String displayValue = value.toString();
        
        // Format satoshi values
        boolean isSatoshiField = satoshiFieldList != null && satoshiFieldList.contains(fieldName);
        if (isSatoshiField && value instanceof Number) {
            try {
                long satoshiValue = ((Number) value).longValue();
                displayValue = com.fc.fc_ajdk.utils.FchUtils.formatSatoshiToCoin(satoshiValue);
            } catch (Exception e) {
                TimberLogger.e(TAG, "Failed to format satoshi value for field %s: %s", fieldName, e.getMessage());
            }
        }
        
        // Format timestamp values from timestamp field list
        boolean isTimestampField = timestampFieldList != null && timestampFieldList.contains(fieldName);
        if (isTimestampField && value instanceof Number) {
            try {
                long timestampValue = ((Number) value).longValue();
                displayValue = com.fc.fc_ajdk.utils.DateUtils.longShortToTime(timestampValue, com.fc.fc_ajdk.utils.DateUtils.LONG_FORMAT);
            } catch (Exception e) {
                TimberLogger.e(TAG, "Failed to format timestamp value for field %s: %s", fieldName, e.getMessage());
            }
        }
        
        // Auto-detect timestamp values by digit count: 10 digits = seconds, 13 digits = milliseconds
        if (!isSatoshiField && !isTimestampField) {
            if (displayValue.matches("\\d{10}")) {
                try {
                    long ts = Long.parseLong(displayValue);
                    displayValue = com.fc.fc_ajdk.utils.DateUtils.longShortToTime(ts, com.fc.fc_ajdk.utils.DateUtils.LONG_FORMAT);
                } catch (Exception e) {
                    TimberLogger.e(TAG, "Failed to auto-format 10-digit timestamp for field %s: %s", fieldName, e.getMessage());
                }
            } else if (displayValue.matches("\\d{13}")) {
                try {
                    long ts = Long.parseLong(displayValue);
                    displayValue = com.fc.fc_ajdk.utils.DateUtils.longToTime(ts, com.fc.fc_ajdk.utils.DateUtils.LONG_FORMAT);
                } catch (Exception e) {
                    TimberLogger.e(TAG, "Failed to auto-format 13-digit timestamp for field %s: %s", fieldName, e.getMessage());
                }
            }
        }
        
        return displayValue;
    }
    
    private void refreshComplexObjectDisplay(String displayName, Object originalValue, String fieldName, int indentLevel, boolean isExpanded) {
        // Find and remove all nested content for this field
        removeNestedContent(fieldName, indentLevel);
        
        // Update the expand icon
        updateExpandIcon(fieldName, indentLevel, isExpanded);
        
        // Note: addNestedContent is not called here because removeNestedContent() 
        // calls setupView() which rebuilds the entire view and will automatically
        // add nested content based on the updated expandedStates map
    }
    
    private void removeNestedContent(String fieldName, int indentLevel) {
        // This is a simplified approach - in a more complex implementation,
        // you might want to tag views or use a more sophisticated removal strategy
        // For now, we'll rebuild the entire detail view when toggling
        setupView();
    }
    
    private void updateExpandIcon(String fieldName, int indentLevel, boolean isExpanded) {
        // This will be handled by the rebuild in removeNestedContent
        // In a more optimized version, you could find and update just the icon
    }
    
    private void addNestedContent(Object value, String parentFieldName, int indentLevel) {
        if (value == null || indentLevel >= MAX_NESTED_DEPTH) {
            return;
        }
        
        try {
            if (value instanceof Collection) {
                addCollectionContent((Collection<?>) value, parentFieldName, indentLevel);
            } else if (value instanceof Map) {
                addMapContent((Map<?, ?>) value, parentFieldName, indentLevel);
            } else if (value instanceof FcEntity) {
                addEntityContent((FcEntity) value, parentFieldName, indentLevel);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error adding nested content for %s: %s", parentFieldName, e.getMessage());
            createFieldRow("Error", "Failed to display nested content", parentFieldName + "_error", null, indentLevel);
        }
    }
    
    private void addCollectionContent(Collection<?> collection, String parentFieldName, int indentLevel) {
        int index = 0;
        for (Object item : collection) {
            String itemFieldName = parentFieldName + "[" + index + "]";
            createFieldRowWithComplexHandling("[" + index + "]", item, itemFieldName, indentLevel);
            index++;
            
            // Limit the number of items displayed to prevent performance issues
            if (index >= 20) {
                createFieldRow("...", "(" + (collection.size() - index) + " more items)", parentFieldName + "_more", null, indentLevel);
                break;
            }
        }
    }
    
    private void addMapContent(Map<?, ?> map, String parentFieldName, int indentLevel) {
        int count = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object key = entry.getKey();
            Object value = entry.getValue();
            
            String keyStr = key != null ? key.toString() : "null";
            String itemFieldName = parentFieldName + "[" + keyStr + "]";
            
            createFieldRowWithComplexHandling(keyStr, value, itemFieldName, indentLevel);
            count++;
            
            // Limit the number of entries displayed
            if (count >= 20) {
                createFieldRow("...", "(" + (map.size() - count) + " more entries)", parentFieldName + "_more", null, indentLevel);
                break;
            }
        }
    }
    
    private void addEntityContent(FcEntity entity, String parentFieldName, int indentLevel) {
        // Get the entity's fields using the same logic as the main entity display
        try {
            // Get current locale language code
            String languageCode = getResources().getConfiguration().getLocales().get(0).getLanguage();

            LinkedHashMap<String, Map<String, String>> fieldNameMap = getOrderedFieldMap(entity.getClass());

            if (fieldNameMap != null && !fieldNameMap.isEmpty()) {
                for (Map.Entry<String, Map<String, String>> entry : fieldNameMap.entrySet()) {
                    String fieldName = entry.getKey();
                    Map<String, String> languageMap = entry.getValue();

                    try {
                        Object value = getFieldValue(entity, fieldName);
                        if (value == null) continue;

                        // Get display name based on current locale (prefer current language, fallback to English, then field name)
                        String displayName = languageMap.get(languageCode);
                        if (displayName == null || displayName.isEmpty()) {
                            displayName = languageMap.getOrDefault("en", fieldName);
                        }
                        if (displayName == null || displayName.isEmpty()) {
                            displayName = fieldName;
                        }

                        String nestedFieldName = parentFieldName + "." + fieldName;
                        createFieldRowWithComplexHandling(displayName, value, nestedFieldName, indentLevel);

                    } catch (Exception e) {
                        TimberLogger.e(TAG, "Failed to get nested field value for %s: %s", fieldName, e.getMessage());
                    }
                }
            } else {
                // Fallback to reflection
                addEntityFieldsUsingReflection(entity, parentFieldName, indentLevel);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error adding entity content for %s: %s", parentFieldName, e.getMessage());
            createFieldRow("Error", "Failed to display entity content", parentFieldName + "_error", null, indentLevel);
        }
    }
    
    private void addEntityFieldsUsingReflection(FcEntity entity, String parentFieldName, int indentLevel) {
        // Build complete class hierarchy from most specific to most general
        List<Class<?>> classHierarchy = new ArrayList<>();
        Class<?> currentClass = entity.getClass();
        
        // Collect all classes in the hierarchy up to and including FcEntity
        while (currentClass != null && currentClass != Object.class) {
            classHierarchy.add(currentClass);
            // Stop after FcEntity (don't go to Object)
            if (currentClass == FcEntity.class) {
                break;
            }
            currentClass = currentClass.getSuperclass();
        }
        
        // Collect all fields from the entire class hierarchy
        List<Field> allFields = new ArrayList<>();
        for (Class<?> clazz : classHierarchy) {
            Field[] fields = clazz.getDeclaredFields();
            allFields.addAll(Arrays.asList(fields));
        }
        
        // Process all fields
        for (Field field : allFields) {
            field.setAccessible(true);
            String fieldName = field.getName();
            
            // Skip static, final, or transient fields
            int modifiers = field.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(modifiers) || 
                java.lang.reflect.Modifier.isTransient(modifiers)) {
                continue;
            }
            
            try {
                Object value = field.get(entity);
                if (value == null) continue;
                
                String nestedFieldName = parentFieldName + "." + fieldName;
                createFieldRowWithComplexHandling(fieldName, value, nestedFieldName, indentLevel);
                
            } catch (IllegalAccessException e) {
                TimberLogger.e(TAG, "Failed to access field %s: %s", fieldName, e.getMessage());
            }
        }
    }

    public FcEntity getCurrentEntity() {
        return currentEntity;
    }

    /**
     * Decrypts cipher content using private key similar to Secret.decryptContent
     */
    private byte[] decryptCipher(String cipher) {
        if (cipher == null || cipher.isEmpty()) {
            return null;
        }

        try {
            // Get private key from FidManager
            FidManager fidManager = FidManager.getInstance();
            if (fidManager == null || fidManager.getLiveKeyInfo() == null) {
                TimberLogger.w(TAG, "Cannot decrypt: FidManager or KeyInfo not available");
                return null;
            }

            byte[] priKey = SecurePrikeyManager.fetchPrikeySilentAndPersistent(fidManager.getLiveKeyInfo().getPrikeyCipher());
            try {
                byte[] symkey = ConfigureManager.getInstance().getSymkey();
                CryptoDataByte cryptoDataByte =  Decryptor.decryptTry(cipher,priKey,symkey, null);
                if (cryptoDataByte.getCode() == 0) {
                    // Successfully decrypted
                    return cryptoDataByte.getData();
                }
            } finally {
                // Erase private key for security
                SecurePrikeyManager.erasePrikey(priKey);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error decrypting cipher: %s", e.getMessage());
        }

        return null;
    }

    /**
     * True when a field is a DID reference: named "did" with a 64-hex content-hash value.
     * Such a DID points to a document stored on DISK that can be downloaded by its hash.
     * Applies to the DID field of any FcEntity (e.g. {@link Protocol#getDid()}).
     */
    private boolean isDidField(String fieldName, String value) {
        return "did".equalsIgnoreCase(fieldName)
                && value != null
                && value.matches("[0-9a-fA-F]{64}");
    }

    /**
     * Downloads the document referenced by a DID via {@link DataSyncManager#downloadByDid},
     * which creates a HAT referencing this object's details and saves the file locally.
     * On success the resulting HAT is opened in {@link HatDetailActivity} so the user can
     * view/play the downloaded file.
     */
    private void downloadDidDocument(String did) {
        if (did == null || did.isEmpty()) return;

        FidManager fidManager = FidManager.getInstance();
        String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
        if (liveFid == null) {
            ToastUtils.makeText(requireContext(), getString(R.string.error_no_live_fid));
            return;
        }

        HatManager hatManager = HatManager.getInstance(requireContext(), liveFid);
        DataSyncManager dataSyncManager = new DataSyncManager(requireContext(), hatManager);

        // Reference the source object's details on the created HAT.
        String hatName = deriveEntityName();
        String hatDesc = currentEntityClass != null
                ? currentEntityClass.getSimpleName()
                    + (currentEntity != null && currentEntity.getId() != null ? " " + currentEntity.getId() : "")
                : null;

        WaitingDialog waitingDialog = new WaitingDialog(requireContext(), getString(R.string.downloading_data));
        waitingDialog.show();

        new Thread(() -> {
            Hat hat = dataSyncManager.downloadByDid(did, hatName, hatDesc);
            requireActivity().runOnUiThread(() -> {
                waitingDialog.dismiss();
                if (hat != null) {
                    ToastUtils.makeText(requireContext(), getString(R.string.download_successful));
                    Intent intent = new Intent(requireContext(), HatDetailActivity.class);
                    intent.putExtra(HatDetailActivity.EXTRA_HAT_ID, hat.getId());
                    startActivity(intent);
                } else {
                    ToastUtils.makeText(requireContext(),
                            getString(R.string.download_failed, dataSyncManager.getLastError()));
                }
            });
        }).start();
    }

    /**
     * Derives a display name for the created HAT from the source object (its "name" or
     * "title" field), falling back to the entity class's simple name.
     */
    private String deriveEntityName() {
        if (currentEntity == null) {
            return currentEntityClass != null ? currentEntityClass.getSimpleName() : null;
        }
        for (String candidate : new String[]{"name", "title"}) {
            try {
                Object v = getFieldValue(currentEntity, candidate);
                if (v instanceof String && !((String) v).isEmpty()) {
                    return (String) v;
                }
            } catch (Exception ignored) {
            }
        }
        return currentEntityClass != null ? currentEntityClass.getSimpleName() : null;
    }

    private boolean isRateableEntityType(Class<? extends FcEntity> entityClass) {
        return Freer.class.isAssignableFrom(entityClass)
                || entityClass == Protocol.class
                || entityClass == Code.class
                || entityClass == Service.class
                || entityClass == App.class
                || entityClass == Team.class;
    }

    private void openRateActivity() {
        if (currentEntity == null || currentEntityClass == null) return;

        if (currentEntityClass == Contact.class) {
            Contact contact = (Contact) currentEntity;
            String fid = contact.getFid();
            if (fid == null || fid.isEmpty()) {
                fid = contact.getId();
            }
            Freer freer = new Freer();
            freer.setId(fid);
            freer.setCid(contact.getCid());
            Intent intent = new Intent(requireContext(), RateFreerActivity.class);
            intent.putExtra(RateFreerActivity.EXTRA_FREER_JSON, freer.toJson());
            startActivity(intent);
        } else if (Freer.class.isAssignableFrom(currentEntityClass)) {
            Intent intent = new Intent(requireContext(), RateFreerActivity.class);
            intent.putExtra(RateFreerActivity.EXTRA_FREER_JSON, currentEntity.toJson());
            startActivity(intent);
        } else {
            Intent intent = new Intent(requireContext(), RateActivity.class);
            if (currentEntityClass == App.class) {
                intent.putExtra(RateActivity.EXTRA_APP_JSON, currentEntity.toJson());
            } else if (currentEntityClass == Code.class) {
                intent.putExtra(RateActivity.EXTRA_CODE_JSON, currentEntity.toJson());
            } else if (currentEntityClass == Protocol.class) {
                intent.putExtra(RateActivity.EXTRA_PROTOCOL_JSON, currentEntity.toJson());
            } else if (currentEntityClass == Service.class) {
                intent.putExtra(RateActivity.EXTRA_SERVICE_JSON, currentEntity.toJson());
            } else if (currentEntityClass == Team.class) {
                intent.putExtra(RateActivity.EXTRA_TEAM_JSON, currentEntity.toJson());
            }
            startActivity(intent);
        }
    }

    /**
     * Fetches Freer information for the given FID and shows it in DetailActivity
     */
    private void fetchAndShowCidInfo(String fid) {
        if (fid == null || fid.isEmpty() || !KeyTools.isGoodFid(fid)) {
            ToastUtils.makeText(requireContext(), getString(R.string.toast_invalid_fid));
            return;
        }

        // Show waiting dialog
        WaitingDialog waitingDialog = new WaitingDialog(requireContext(), getString(R.string.loading_fid_info));
        waitingDialog.show();

        // Perform network request in background thread
        new Thread(() -> {
            try {
                // Get FAPI client from ApiCenter
                ApiCenter apiCenter = ApiCenter.getInstance();
                if (apiCenter == null) {
                    requireActivity().runOnUiThread(() -> {
                        // Dismiss waiting dialog
                        waitingDialog.dismiss();
                        ToastUtils.makeText(requireContext(), getString(R.string.toast_api_center_unavailable));
                    });
                    return;
                }

                FapiClient fapiClient = (FapiClient) apiCenter.getClient(com.fc.fc_ajdk.data.feipData.Service.ServiceType.FAPI_No1_NrC7);
                if (fapiClient == null) {
                    requireActivity().runOnUiThread(() -> {
                        // Dismiss waiting dialog
                        waitingDialog.dismiss();
                        ToastUtils.makeText(requireContext(), getString(R.string.toast_fapi_client_unavailable));
                    });
                    return;
                }

                // Fetch Freer info
                Freer freerInfo = fapiClient.getFreer(fid);

                // Update UI on main thread
                requireActivity().runOnUiThread(() -> {
                    // Dismiss waiting dialog
                    waitingDialog.dismiss();

                    if (freerInfo != null) {
                        // Start DetailActivity with Freer info
                        Intent intent = new Intent(requireContext(), DetailActivity.class);
                        intent.putExtra(DetailActivity.EXTRA_ENTITY_JSON, freerInfo.toJson());
                        intent.putExtra(DetailActivity.EXTRA_ENTITY_CLASS, Freer.class.getName());
                        startActivity(intent);
                    } else {
                        ToastUtils.makeText(requireContext(), getString(R.string.toast_failed_load_fid_info));
                    }
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error fetching Freer info for FID %s: %s", fid, e.getMessage());
                requireActivity().runOnUiThread(() -> {
                    // Dismiss waiting dialog
                    waitingDialog.dismiss();
                    ToastUtils.makeText(requireContext(), getString(R.string.toast_error_loading_fid_info, e.getMessage()));
                });
            }
        }).start();
    }
} 