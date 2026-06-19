package com.fc.freer.utils;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;

import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.manager.FidManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class ServiceCardContainer {
    private static final String TAG = "ServiceCardContainer";
    private final Context context;
    private final ViewGroup serviceListContainer;
    private final List<Service> serviceList;
    private final List<CompoundButton> checkBoxes;
    private final ChooseMode chooseMode;
    private OnServiceListChangedListener onServiceListChangedListener;
    private final List<String> menuItems;
    private OnMenuItemClickListener onMenuItemClickListener;
    private OnServiceClickListener onServiceClickListener;
    private OnServiceRemoveListener onServiceRemoveListener;
    private OnEditIconClickListener onEditIconClickListener;
    private OnRateIconClickListener onRateIconClickListener;
    private OnOffChainIconClickListener onOffChainIconClickListener;
    private OnClearIconClickListener onClearIconClickListener;
    private ActivityResultLauncher<Intent> updateServiceLauncher;
    private boolean hideEditButton = false;
    private boolean showClearButton = false;

    public interface OnServiceListChangedListener {
        void onServiceListChanged(List<Service> updatedServiceList);
    }

    public interface OnMenuItemClickListener {
        void onMenuItemClick(String menuItem, Service service);
    }

    public interface OnServiceClickListener {
        void onServiceClick(Service service);
    }

    public interface OnServiceRemoveListener {
        void onServiceRemove(Service service);
    }

    public interface OnEditIconClickListener {
        void onEditIconClick(Service service);
    }

    public interface OnRateIconClickListener {
        void onRateIconClick(Service service);
    }

    public interface OnOffChainIconClickListener {
        void onOffChainIconClick(Service service);
    }

    public interface OnClearIconClickListener {
        void onClearIconClick(Service service);
    }

    public ServiceCardContainer(Context context, LinearLayout serviceListContainer, ChooseMode chooseMode) {
        this(context, serviceListContainer, chooseMode, null);
    }

    public ServiceCardContainer(Context context, LinearLayout serviceListContainer, ChooseMode chooseMode, List<String> menuItems) {
        this.context = context;
        this.serviceListContainer = serviceListContainer;
        this.serviceList = new ArrayList<>();
        this.checkBoxes = new ArrayList<>();
        this.chooseMode = chooseMode;
        this.menuItems = menuItems;
    }

    public void setOnServiceListChangedListener(OnServiceListChangedListener listener) {
        this.onServiceListChangedListener = listener;
    }

    public void setOnMenuItemClickListener(OnMenuItemClickListener listener) {
        this.onMenuItemClickListener = listener;
    }

    public void setOnServiceClickListener(OnServiceClickListener listener) {
        this.onServiceClickListener = listener;
    }

    public void setOnServiceRemoveListener(OnServiceRemoveListener listener) {
        this.onServiceRemoveListener = listener;
    }

    public void setOnEditIconClickListener(OnEditIconClickListener listener) {
        this.onEditIconClickListener = listener;
    }

    public void setOnRateIconClickListener(OnRateIconClickListener listener) {
        this.onRateIconClickListener = listener;
    }

    public void setOnOffChainIconClickListener(OnOffChainIconClickListener listener) {
        this.onOffChainIconClickListener = listener;
    }

    public void setOnClearIconClickListener(OnClearIconClickListener listener) {
        this.onClearIconClickListener = listener;
    }

    public void setHideEditButton(boolean hide) {
        this.hideEditButton = hide;
    }

    public void setShowClearButton(boolean show) {
        this.showClearButton = show;
    }

    public void setUpdateServiceLauncher(ActivityResultLauncher<Intent> launcher) {
        this.updateServiceLauncher = launcher;
    }

    private void notifyServiceListChanged() {
        if (onServiceListChangedListener != null) {
            onServiceListChangedListener.onServiceListChanged(new ArrayList<>(serviceList));
        }
    }

    @Nullable
    public Map<String, String> getCidMap(List<Service> serviceList, Context context) {
        List<String> fidList = new ArrayList<>();
        for (Service service : serviceList) {
            fidList.add(service.getOwner());
        }
        Map<String, String> cidMap = null;
        if (!fidList.isEmpty()) {
            CidFidManager cidFidManager = CidFidManager.getInstance(context);
            cidMap = cidFidManager.getCidsByFids(fidList);
        }
        return cidMap;
    }

    public void addServiceCard(Service service, Map<String, String> cidMap) {
        addServiceCardAtPosition(service, serviceList.size(), cidMap);
    }

    private void showServiceActivity(Service service) {
        Intent intent = new Intent(context, com.fc.freer.ui.DetailActivity.class);
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_JSON, service.toJson());
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_CLASS, Service.class.getName());
        context.startActivity(intent);
    }

    public List<Service> getSelectedServices() {
        List<Service> selectedServices = new ArrayList<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (checkBoxes.get(i).isChecked()) {
                selectedServices.add(serviceList.get(i));
            }
        }
        return selectedServices;
    }

    public void clearAll() {
        serviceList.clear();
        serviceListContainer.removeAllViews();
        checkBoxes.clear();
    }

    public List<Service> getServiceList() {
        return serviceList;
    }

    public void selectAll(boolean selected) {
        if (chooseMode != ChooseMode.CHOOSE_MULTI) {
            return;
        }

        for (CompoundButton checkBox : checkBoxes) {
            checkBox.setChecked(selected);
        }
    }

    public boolean areAllSelected() {
        if (chooseMode != ChooseMode.CHOOSE_MULTI || checkBoxes.isEmpty()) {
            return false;
        }

        for (CompoundButton checkBox : checkBoxes) {
            if (!checkBox.isChecked()) {
                return false;
            }
        }
        return true;
    }

    public boolean areNoneSelected() {
        if (chooseMode != ChooseMode.CHOOSE_MULTI || checkBoxes.isEmpty()) {
            return true;
        }

        for (CompoundButton checkBox : checkBoxes) {
            if (checkBox.isChecked()) {
                return false;
            }
        }
        return true;
    }

    public void removeSelectedServices() {
        if (chooseMode != ChooseMode.CHOOSE_MULTI || checkBoxes.isEmpty()) {
            return;
        }

        List<Integer> selectedIndices = new ArrayList<>();
        for (int i = checkBoxes.size() - 1; i >= 0; i--) {
            if (checkBoxes.get(i).isChecked()) {
                selectedIndices.add(i);
            }
        }

        for (int index : selectedIndices) {
            if (index < serviceListContainer.getChildCount()) {
                serviceListContainer.removeViewAt(index);
            }
            if (index < serviceList.size()) {
                serviceList.remove(index);
            }
            if (index < checkBoxes.size()) {
                checkBoxes.remove(index);
            }
        }

        notifyServiceListChanged();
    }

    public void removeFromBeginning(int count) {
        if (count <= 0 || count > serviceList.size()) {
            return;
        }

        for (int i = 0; i < count; i++) {
            serviceListContainer.removeViewAt(0);
        }

        for (int i = 0; i < count; i++) {
            serviceList.remove(0);
            if (!checkBoxes.isEmpty()) {
                checkBoxes.remove(0);
            }
        }

        notifyServiceListChanged();
    }

    public void removeFromEnd(int count) {
        if (count <= 0 || count > serviceList.size()) {
            return;
        }

        int size = serviceList.size();
        for (int i = 0; i < count; i++) {
            serviceListContainer.removeViewAt(serviceListContainer.getChildCount() - 1);
        }

        for (int i = 0; i < count; i++) {
            serviceList.remove(size - 1 - i);
            if (checkBoxes.size() > size - 1 - i) {
                checkBoxes.remove(size - 1 - i);
            }
        }

        notifyServiceListChanged();
    }

    public void addServiceCardsToBeginning(List<Service> servicesToAdd) {
        if (servicesToAdd == null || servicesToAdd.isEmpty()) {
            return;
        }
        Map<String, String> cidMap = getCidMap(servicesToAdd, context);
        for (int i = servicesToAdd.size() - 1; i >= 0; i--) {
            Service service = servicesToAdd.get(i);
            addServiceCardAtPosition(service, 0, cidMap);
        }
    }

    public void addServiceCardsToBeginningWithBottomRemoval(List<Service> servicesToAdd, int maxSize) {
        if (servicesToAdd == null || servicesToAdd.isEmpty()) {
            return;
        }

        addServiceCardsToBeginning(servicesToAdd);

        int currentSize = serviceList.size();
        int removeCount = Math.max(0, currentSize - maxSize);
        if (removeCount > 0) {
            removeFromEnd(removeCount);
        } else {
            notifyServiceListChanged();
        }
    }

    public void addServiceCardsToEndWithTopRemoval(List<Service> servicesToAdd, int maxSize) {
        if (servicesToAdd == null || servicesToAdd.isEmpty()) {
            return;
        }

        Map<String, String> cidMap = getCidMap(servicesToAdd, context);
        for (Service service : servicesToAdd) {
            addServiceCardAtPosition(service, serviceList.size(), cidMap);
        }

        int currentSize = serviceList.size();
        int removeCount = Math.max(0, currentSize - maxSize);
        if (removeCount > 0) {
            removeFromBeginning(removeCount);
        } else {
            notifyServiceListChanged();
        }
    }

    public void sortByStdName(boolean ascending, boolean enableSort) {
        sortServices(enableSort, (pair1, pair2) -> {
            String name1 = pair1.service.getStdName();
            String name2 = pair2.service.getStdName();
            return compareNullable(name1, name2, ascending);
        });
    }

    public void sortByOwner(boolean ascending, boolean enableSort) {
        sortServices(enableSort, (pair1, pair2) -> {
            String owner1 = pair1.service.getOwner();
            String owner2 = pair2.service.getOwner();
            return compareNullable(owner1, owner2, ascending);
        });
    }

    public void sortByLastTime(boolean ascending, boolean enableSort) {
        sortServices(enableSort, (pair1, pair2) -> {
            Long lastTime1 = pair1.service.getLastTime();
            Long lastTime2 = pair2.service.getLastTime();
            return compareNullable(lastTime1, lastTime2, ascending);
        });
    }

    public void sortByTCdd(boolean ascending, boolean enableSort) {
        sortServices(enableSort, (pair1, pair2) -> {
            Long tCdd1 = pair1.service.gettCdd();
            Long tCdd2 = pair2.service.gettCdd();
            return compareNullable(tCdd1, tCdd2, ascending);
        });
    }

    public void sortByTRate(boolean ascending, boolean enableSort) {
        sortServices(enableSort, (pair1, pair2) -> {
            Float tRate1 = pair1.service.gettRate();
            Float tRate2 = pair2.service.gettRate();
            return compareNullable(tRate1, tRate2, ascending);
        });
    }

    private void addServiceCardAtPosition(Service service, int position, Map<String, String> cidMap) {
        View cardView = createCardView();
        CompoundButton checkBox = setupCardViewInteractions(cardView, service, cidMap);

        serviceListContainer.addView(cardView, position);
        serviceList.add(position, service);
        if (checkBox != null) {
            checkBoxes.add(position, checkBox);
        }
    }

    private View createCardView() {
        return LayoutInflater.from(context).inflate(R.layout.item_service_card, serviceListContainer, false);
    }

    private CompoundButton setupCardViewInteractions(View cardView, Service service, Map<String, String> cidMap) {
        CompoundButton checkBox = setupCheckBox(cardView);
        setupCardData(cardView, service, cidMap);
        setupClickListeners(cardView, service);
        setupButtons(cardView, service);
        return checkBox;
    }

    private CompoundButton setupCheckBox(View cardView) {
        if (chooseMode == ChooseMode.CHOOSE_ONE_RETURN || chooseMode == ChooseMode.WITHOUT_CHOOSE) {
            return null;
        }

        CompoundButton checkBox = cardView.findViewById(R.id.service_checkbox);
        checkBox.setVisibility(VISIBLE);
        if (chooseMode == ChooseMode.CHOOSE_ONE) {
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    for (CompoundButton cb : checkBoxes) {
                        if (cb != checkBox) {
                            cb.setChecked(false);
                        }
                    }
                }
            });
        } else {
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> notifyServiceListChanged());
        }
        return checkBox;
    }

    private void setupCardData(View cardView, Service service, Map<String, String> cidMap) {
        ImageView ownerAvatarView = cardView.findViewById(R.id.service_owner_avatar);
        TextView stdNameValue = cardView.findViewById(R.id.service_std_name_value);
        TextView ownerValue = cardView.findViewById(R.id.service_owner_value);
        TextView typeValue = cardView.findViewById(R.id.service_type_value);
        TextView tCddValue = cardView.findViewById(R.id.service_tcdd_value);
        TextView tRateValue = cardView.findViewById(R.id.service_trate_value);

        setupAvatar(ownerAvatarView, service.getOwner());
        setTextValue(stdNameValue, service.getStdName());

        if (cidMap != null && cidMap.get(service.getOwner()) != null) {
            setTextValue(ownerValue, cidMap.get(service.getOwner()));
        } else {
            setTextValue(ownerValue, service.getOwner());
        }

        // Format type
        if (service.fetchServiceType() != null) {
            setTextValue(typeValue, service.fetchServiceType().toString());
        } else {
            setTextValue(typeValue, "");
        }

        // Format tCdd
        if (service.gettCdd() != null) {
            setTextValue(tCddValue, formatNumber(service.gettCdd()));
        } else {
            setTextValue(tCddValue, "0");
        }

        // Format tRate
        if (service.gettRate() != null) {
            setTextValue(tRateValue, String.format("%.1f", service.gettRate()));
        } else {
            setTextValue(tRateValue, "0.0");
        }
    }

    private String formatNumber(Long number) {
        if (number == null) return "0";
        if (number >= 1_000_000_000) {
            return String.format("%.1fB", number / 1_000_000_000.0);
        } else if (number >= 1_000_000) {
            return String.format("%.1fM", number / 1_000_000.0);
        } else if (number >= 1_000) {
            return String.format("%.1fK", number / 1_000.0);
        }
        return String.valueOf(number);
    }

    private void setupAvatar(ImageView avatarView, String fid) {
        if (fid != null && !fid.isEmpty()) {
            try {
                AvatarManager avatarManager = AvatarManager.getInstance(context);
                Bitmap avatarBitmap = avatarManager.getAvatarBitmap(fid);
                if (avatarBitmap != null) {
                    avatarView.setImageBitmap(avatarBitmap);
                } else {
                    avatarView.setImageResource(R.drawable.container_outline);
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Failed to load avatar for FID %s: %s", fid, e.getMessage());
                avatarView.setImageResource(R.drawable.container_outline);
            }
        } else {
            avatarView.setImageResource(R.drawable.container_outline);
        }
    }

    private void setTextValue(TextView textView, String value) {
        textView.setText(value != null ? value : "");
    }

    private void setupClickListeners(View cardView, Service service) {
        TextView stdNameValue = cardView.findViewById(R.id.service_std_name_value);
        TextView ownerValue = cardView.findViewById(R.id.service_owner_value);

        View.OnClickListener clickListener = v -> {
            if (onServiceClickListener != null) {
                onServiceClickListener.onServiceClick(service);
            } else {
                showServiceActivity(service);
            }
        };
        View.OnLongClickListener longPressListener = createLongPressListener(cardView, service);

        cardView.setOnClickListener(clickListener);
        cardView.setOnLongClickListener(longPressListener);

        if (stdNameValue != null) {
            stdNameValue.setOnClickListener(clickListener);
            stdNameValue.setOnLongClickListener(longPressListener);
        }

        if (ownerValue != null) {
            ownerValue.setOnClickListener(clickListener);
            ownerValue.setOnLongClickListener(longPressListener);
        }
    }

    private View.OnLongClickListener createLongPressListener(View cardView, Service service) {
        return v -> {
            if (menuItems != null && !menuItems.isEmpty()) {
                PopupMenu popup = new PopupMenu(context, v);
                for (String menuItem : menuItems) {
                    popup.getMenu().add(menuItem);
                }

                popup.setOnMenuItemClickListener(item -> {
                    if (item.getTitle() == null) return false;
                    String title = item.getTitle().toString();
                    if ("Delete".equals(title)) {
                        removeServiceCard(cardView, service);
                        return true;
                    } else if (onMenuItemClickListener != null) {
                        onMenuItemClickListener.onMenuItemClick(title, service);
                        return true;
                    }
                    return false;
                });

                popup.show();
                return true;
            }
            return false;
        };
    }

    private void removeServiceCard(View cardView, Service service) {
        serviceListContainer.removeView(cardView);
        int index = serviceList.indexOf(service);
        if (index != -1) {
            serviceList.remove(index);
            if (index < checkBoxes.size()) {
                checkBoxes.remove(index);
            }
        }
        notifyServiceListChanged();
    }

    public boolean removeServiceById(String serviceId) {
        if (serviceId == null) return false;

        for (int i = 0; i < serviceList.size(); i++) {
            Service service = serviceList.get(i);
            if (serviceId.equals(service.getId())) {
                if (i < serviceListContainer.getChildCount()) {
                    serviceListContainer.removeViewAt(i);
                }
                serviceList.remove(i);
                if (i < checkBoxes.size()) {
                    checkBoxes.remove(i);
                }
                notifyServiceListChanged();
                return true;
            }
        }
        return false;
    }

    public boolean updateServiceCard(Service updatedService) {
        if (updatedService == null || updatedService.getId() == null) return false;

        for (int i = 0; i < serviceList.size(); i++) {
            Service service = serviceList.get(i);
            if (updatedService.getId().equals(service.getId())) {
                serviceList.set(i, updatedService);

                View cardView = serviceListContainer.getChildAt(i);
                if (cardView != null) {
                    Map<String, String> cidMap = getCidMap(java.util.Collections.singletonList(updatedService), context);
                    setupCardData(cardView, updatedService, cidMap);
                    setupButtons(cardView, updatedService);
                }

                notifyServiceListChanged();
                return true;
            }
        }
        return false;
    }

    private void setupButtons(View cardView, Service service) {
        ImageButton chainStatusButton = cardView.findViewById(R.id.service_chain_status_button);
        ImageButton editButton = cardView.findViewById(R.id.service_edit_button);
        ImageButton rateButton = cardView.findViewById(R.id.service_rate_button);

        // Setup chain status button
        if (chainStatusButton != null) {
            Boolean onChain = service.getOnChain();
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
            
            if (liveFid != null && liveFid.equals(service.getOwner())) {
                chainStatusButton.setVisibility(VISIBLE);
                
                if (Boolean.TRUE.equals(onChain)) {
                    chainStatusButton.setImageResource(R.drawable.ic_on_chain);
                    chainStatusButton.setOnClickListener(null);
                    chainStatusButton.setClickable(false);
                } else if (onChain == null) {
                    chainStatusButton.setImageResource(R.drawable.ic_on_chain_unknown);
                    chainStatusButton.setOnClickListener(null);
                    chainStatusButton.setClickable(false);
                } else {
                    chainStatusButton.setImageResource(R.drawable.ic_off_chain);
                    chainStatusButton.setClickable(true);
                    chainStatusButton.setOnClickListener(v -> {
                        if (onOffChainIconClickListener != null) {
                            onOffChainIconClickListener.onOffChainIconClick(service);
                        }
                    });
                }
            } else {
                chainStatusButton.setVisibility(GONE);
            }
        }

        if (editButton != null) {
            if (hideEditButton) {
                editButton.setVisibility(GONE);
            } else if (showClearButton) {
                editButton.setVisibility(VISIBLE);
                editButton.setImageResource(R.drawable.ic_clear);
                editButton.setContentDescription(context.getString(R.string.remove));
                editButton.setOnClickListener(v -> {
                    if (onClearIconClickListener != null) {
                        onClearIconClickListener.onClearIconClick(service);
                    } else {
                        removeServiceCard(cardView, service);
                    }
                });
            } else {
                FidManager fidManager = FidManager.getInstance();
                String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
                if (liveFid != null && liveFid.equals(service.getOwner())) {
                    editButton.setVisibility(VISIBLE);
                    editButton.setImageResource(R.drawable.ic_edit);
                    editButton.setContentDescription(context.getString(R.string.edit));
                    editButton.setOnClickListener(v -> {
                        if (onEditIconClickListener != null) {
                            onEditIconClickListener.onEditIconClick(service);
                        }
                    });
                } else {
                    editButton.setVisibility(GONE);
                }
            }
        }

        if (rateButton != null) {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
            if (liveFid != null && liveFid.equals(service.getOwner())) {
                rateButton.setVisibility(GONE);
            } else {
                rateButton.setVisibility(VISIBLE);
                rateButton.setOnClickListener(v -> {
                    if (onRateIconClickListener != null) {
                        onRateIconClickListener.onRateIconClick(service);
                    }
                });
            }
        }
    }

    private void sortServices(boolean enableSort, Comparator<ServiceViewPair> comparator) {
        if (serviceList.isEmpty() || !enableSort) {
            return;
        }

        List<ServiceViewPair> pairs = createServiceViewPairs();
        pairs.sort(comparator);
        updateListsFromPairs(pairs);
    }

    private List<ServiceViewPair> createServiceViewPairs() {
        List<ServiceViewPair> pairs = new ArrayList<>();
        for (int i = 0; i < serviceList.size(); i++) {
            Service service = serviceList.get(i);
            View cardView = serviceListContainer.getChildAt(i);
            CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
            pairs.add(new ServiceViewPair(service, cardView, checkBox));
        }
        return pairs;
    }

    private void updateListsFromPairs(List<ServiceViewPair> pairs) {
        serviceList.clear();
        checkBoxes.clear();
        serviceListContainer.removeAllViews();

        for (ServiceViewPair pair : pairs) {
            serviceList.add(pair.service);
            if (pair.checkBox != null) {
                checkBoxes.add(pair.checkBox);
            }
            serviceListContainer.addView(pair.cardView);
        }
    }

    private <T extends Comparable<T>> int compareNullable(T value1, T value2, boolean ascending) {
        if (value1 == null && value2 == null) return 0;
        if (value1 == null) return 1;
        if (value2 == null) return -1;
        return ascending ? value1.compareTo(value2) : value2.compareTo(value1);
    }

    private record ServiceViewPair(Service service, View cardView, CompoundButton checkBox) {
    }
}
