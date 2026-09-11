package com.fc.freer.utils;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;


import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.TimberLogger;

import com.fc.freer.R;
import com.fc.freer.initiate.ClientGroup;
import com.fc.freer.manager.FidManager;
import com.fc.fc_ajdk.fapi.client.ApiAccount;
import com.fc.freer.manager.AvatarManager;
import com.fc.fc_ajdk.fapi.client.ApiProvider;
import com.fc.freer.ui.DetailActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class ApiCardContainer {
    private static final String TAG = "ApiCardContainer";
    private final Context context;
    private final ViewGroup apiCardsContainer;
    private final List<ApiProvider> apiProviderList;
    private final List<View> cardViews;
    private final AvatarManager avatarManager;
    private final boolean isSelectable;

    private ApiProvider selectedApiProvider;
    private View selectedCardView;
    private OnApiCardClickListener onApiCardClickListener;
    private OnConverterClickListener onConverterClickListener;

    public interface OnApiCardClickListener {
        void onApiCardClick(ApiProvider apiProvider, int position);
    }

    public interface OnConverterClickListener {
        void onConverterClick(ApiProvider apiProvider, Service.ServiceType serviceType, int index);
    }

    public ApiCardContainer(Context context, LinearLayout apiCardsContainer) {
        this(context, apiCardsContainer, false);
    }

    public ApiCardContainer(Context context, LinearLayout apiCardsContainer, boolean isSelectable) {
        this.context = context;
        this.apiCardsContainer = apiCardsContainer;
        this.apiProviderList = new ArrayList<>();
        this.cardViews = new ArrayList<>();
        this.avatarManager = AvatarManager.getInstance(context);
        this.isSelectable = isSelectable;
    }

    public void setOnApiCardClickListener(OnApiCardClickListener listener) {
        this.onApiCardClickListener = listener;
    }

    public void setOnConverterClickListener(OnConverterClickListener listener) {
        this.onConverterClickListener = listener;
    }

    /**
     * Add an API card to the container
     *
     * @param serviceType   The service type for this API
     * @param index         The account index
     * @param apiProvider   The API provider data
     * @param showConverter Whether to show the converter icon
     */
    public void addApiCard(Service.ServiceType serviceType, int index, ApiProvider apiProvider, boolean showConverter) {
        View cardView = LayoutInflater.from(context).inflate(R.layout.item_api_card, apiCardsContainer, false);

        // Get views
        TextView typeTextView = cardView.findViewById(R.id.api_type);
        TextView indexTextView = cardView.findViewById(R.id.api_index);
        TextView idTextView = cardView.findViewById(R.id.api_id);
        TextView nameTextView = cardView.findViewById(R.id.api_name);
        TextView apiUrlTextView = cardView.findViewById(R.id.api_url);

        LinearLayout ownerContainer = cardView.findViewById(R.id.owner_container);
        ImageView ownerAvatarImageView = cardView.findViewById(R.id.owner_avatar);
        TextView ownerTextView = cardView.findViewById(R.id.api_owner);

        LinearLayout dealerContainer = cardView.findViewById(R.id.dealer_container);
        ImageView dealerAvatarImageView = cardView.findViewById(R.id.dealer_avatar);
        TextView dealerTextView = cardView.findViewById(R.id.api_dealer);

        TextView birthTimeTextView = cardView.findViewById(R.id.api_birth_time);
        ImageView rateIcon = cardView.findViewById(R.id.api_rate_icon);
        TextView rateTextView = cardView.findViewById(R.id.api_rate);
        ImageView cddIcon = cardView.findViewById(R.id.api_cdd_icon);
        TextView cddTextView = cardView.findViewById(R.id.api_cdd);

        LinearLayout restContainer = cardView.findViewById(R.id.rest_container);
        TextView restTextView = cardView.findViewById(R.id.api_rest);

        ImageView converterIcon = cardView.findViewById(R.id.api_converter_icon);

        // Set basic info
        typeTextView.setText(serviceType != null ? serviceType.toString() : "UNKNOWN");
        indexTextView.setText(String.format("#%d", index + 1));

        if (apiProvider.getId() != null) {
            idTextView.setText(apiProvider.getId());

            // Add click listener to show Service details
            idTextView.setOnClickListener(v -> {
                showServiceDetails(apiProvider);
            });
        }

        if (apiProvider.getStdName() != null) {
            nameTextView.setText(apiProvider.getStdName());
        }

        if (apiProvider.getApiUrl() != null) {
            apiUrlTextView.setText(apiProvider.getApiUrl());
        }

        // Handle owner (only if it's a good FID)
        String owner = apiProvider.getOwner();
        if (owner != null && KeyTools.isGoodFid(owner)) {
            ownerContainer.setVisibility(View.VISIBLE);
            com.fc.freer.nobody.NobodyUi.setName(ownerTextView, owner, owner);

            // Load owner avatar
            Bitmap ownerAvatarBitmap = avatarManager.getAvatarBitmap(owner);
            if (ownerAvatarBitmap != null) {
                ownerAvatarImageView.setImageBitmap(ownerAvatarBitmap);
            }

            // Add click listener to show FreerInfo details
            String finalOwner = owner;
            ownerTextView.setOnClickListener(v -> {
                showCidInfoDetails(finalOwner);
            });
        } else {
            ownerContainer.setVisibility(View.GONE);
        }

        // Handle dealer (only if dealerPubkey is not null)
        String dealerPubkey = apiProvider.getDealerPubkey();
        if (dealerPubkey != null) {
            dealerContainer.setVisibility(View.VISIBLE);

            // Convert pubkey to FID
            try {
                String dealerFid = KeyTools.pubkeyToFchAddr(dealerPubkey);
                com.fc.freer.nobody.NobodyUi.setName(dealerTextView, dealerFid, dealerFid);

                // Load dealer avatar
                Bitmap dealerAvatarBitmap = avatarManager.getAvatarBitmap(dealerFid);
                if (dealerAvatarBitmap != null) {
                    dealerAvatarImageView.setImageBitmap(dealerAvatarBitmap);
                }
            } catch (Exception e) {
                dealerContainer.setVisibility(View.GONE);
            }
        } else {
            dealerContainer.setVisibility(View.GONE);
        }

        // Handle service info (birth time, tRate, Cdd)
        // ApiProvider now extends Service, so call methods directly

        // Handle birth time
        if (apiProvider.getBirthTime() != null) {
            try {
                java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
                String formattedDate = dateFormat.format(new java.util.Date(apiProvider.getBirthTime() * 1000L));
                birthTimeTextView.setText(formattedDate);
                birthTimeTextView.setVisibility(View.VISIBLE);
            } catch (Exception e) {
                birthTimeTextView.setVisibility(View.GONE);
            }
        } else {
            birthTimeTextView.setVisibility(View.GONE);
        }

        // Handle tRate
        if (apiProvider.gettRate() != null) {
            rateIcon.setVisibility(View.VISIBLE);
            rateTextView.setVisibility(View.VISIBLE);
            rateTextView.setText(String.valueOf(apiProvider.gettRate()));
        } else {
            rateIcon.setVisibility(View.GONE);
            rateTextView.setVisibility(View.GONE);
        }

        // Handle Cdd
        if (apiProvider.gettCdd() != null) {
            cddIcon.setVisibility(View.VISIBLE);
            cddTextView.setVisibility(View.VISIBLE);
            cddTextView.setText(String.valueOf(apiProvider.gettCdd()));
        } else {
            cddIcon.setVisibility(View.GONE);
            cddTextView.setVisibility(View.GONE);
        }

        // Handle state icon
        updateCardState(cardView, apiProvider);

        // Handle converter icon
        if (showConverter) {
            converterIcon.setVisibility(View.VISIBLE);
            converterIcon.setOnClickListener(v -> {
                if (onConverterClickListener != null) {
                    onConverterClickListener.onConverterClick(apiProvider, serviceType, index);
                }
            });
        } else {
            converterIcon.setVisibility(View.GONE);
        }

        // Make card clickable/selectable
        if (isSelectable) {
            cardView.setClickable(true);
            cardView.setFocusable(true);
            int position = apiProviderList.size();
            cardView.setOnClickListener(v -> {
                selectCard(cardView, apiProvider);
                if (onApiCardClickListener != null) {
                    onApiCardClickListener.onApiCardClick(apiProvider, position);
                }
            });
        }

        apiCardsContainer.addView(cardView);
        apiProviderList.add(apiProvider);
        cardViews.add(cardView);
    }

    /**
     * Update the state icon of a specific card
     */
    public void updateCardState(View apiCard, ApiProvider apiProvider) {
        ImageView stateIcon = apiCard.findViewById(R.id.api_state_icon);
        // ApiProvider now extends Service, so check directly
        Boolean closed = apiProvider.getClosed();
        Boolean active = apiProvider.getActive();

        if (closed != null && closed) {
            stateIcon.setVisibility(View.VISIBLE);
            stateIcon.setImageResource(R.drawable.ic_closed);
        } else if (active != null && !active) {
            stateIcon.setVisibility(View.VISIBLE);
            stateIcon.setImageResource(R.drawable.ic_paused);
        } else if (active != null) {
            stateIcon.setVisibility(View.VISIBLE);
            stateIcon.setImageResource(R.drawable.ic_running);
        } else {
            stateIcon.setVisibility(View.GONE);
        }

        String apiAccountId = ApiAccount.makeApiAccountId(apiProvider.getId(), FidManager.getInstance().getMainFid());
        if(apiAccountId==null)return;

        ClientGroup clientGroup = ApiCenter.getInstance().getClientGroup(apiProvider.fetchServiceType());
        if(clientGroup==null)return;

        ApiAccount apiAccount = clientGroup.getApiAccount(apiAccountId);
        if(apiAccount==null)return;
        if(apiAccount.getClient()!=null && apiAccount.getClient() instanceof com.fc.fc_ajdk.fapi.client.FapiClient fapiClient)
            calculateAndDisplayRest(apiProvider,fapiClient,apiCard.findViewById(R.id.rest_container),apiCard.findViewById(R.id.api_rest));
    }

    /**
     * Update the state icon of a card at a specific position
     */
    public void updateCardStateAtPosition(int position, ApiProvider apiProvider) { //TODO
        if (position >= 0 && position < cardViews.size()) {
            View cardView = cardViews.get(position);
            updateCardState(cardView, apiProvider);
            // Also update the provider with the new service data
            if (position < apiProviderList.size()) {
                apiProviderList.get(position).updateWithService(apiProvider);
            }
        }
    }

    /**
     * Select a card and update selection state
     */
    private void selectCard(View cardView, ApiProvider apiProvider) {
        if (!isSelectable) {
            return;
        }

        // Reset previous selection
        if (selectedCardView != null) {
            selectedCardView.setBackgroundResource(R.drawable.rounded_textbox_background);
        }

        // Set new selection
        selectedCardView = cardView;
        selectedApiProvider = apiProvider;

        // Highlight selected card
        cardView.setBackgroundResource(R.drawable.selected_card_background);
    }

    /**
     * Clear current selection
     */
    public void clearSelection() {
        if (selectedCardView != null) {
            selectedCardView.setBackgroundResource(R.drawable.rounded_textbox_background);
            selectedCardView = null;
            selectedApiProvider = null;
        }
    }

    /**
     * Clear all cards
     */
    public void clearAll() {
        apiProviderList.clear();
        cardViews.clear();
        apiCardsContainer.removeAllViews();
        selectedApiProvider = null;
        selectedCardView = null;
    }

    /**
     * Get the selected API provider
     */
    public ApiProvider getSelectedApiProvider() {
        return selectedApiProvider;
    }

    /**
     * Get all API providers
     */
    public List<ApiProvider> getApiProviderList() {
        return apiProviderList;
    }

    /**
     * Get card view at position
     */
    public View getCardViewAtPosition(int position) {
        if (position >= 0 && position < cardViews.size()) {
            return cardViews.get(position);
        }
        return null;
    }

    /**
     * Select card at position (useful for auto-selecting first card)
     */
    public void selectCardAtPosition(int position) {
        if (position >= 0 && position < cardViews.size() && position < apiProviderList.size()) {
            selectCard(cardViews.get(position), apiProviderList.get(position));
        }
    }

    /**
     * Get the number of cards
     */
    public int getCardCount() {
        return apiProviderList.size();
    }

    /**
     * Show Service details in DetailActivity
     */
    private void showServiceDetails(ApiProvider apiProvider) {
        new Thread(() -> {
            try {
                // ApiProvider now extends Service
                // If stdName is null, try to fetch updated info from API
                if (apiProvider.getStdName() == null) {
                    com.fc.fc_ajdk.fapi.client.FapiClient fapiClient = (com.fc.fc_ajdk.fapi.client.FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
                    if (fapiClient != null && apiProvider.getId() != null) {
                        Map<String, Service> serviceMap = fapiClient.serviceByIds(Collections.singletonList(apiProvider.getId()));
                        if (serviceMap != null && serviceMap.containsKey(apiProvider.getId())) {
                            Service fetchedService = serviceMap.get(apiProvider.getId());
                            apiProvider.updateWithService(fetchedService);
                        }
                    }
                }

                // Show Service details in DetailActivity (apiProvider is now a Service)
                ((android.app.Activity) context).runOnUiThread(() -> {
                    Intent intent = new Intent(context, DetailActivity.class);
                    intent.putExtra(DetailActivity.EXTRA_ENTITY_JSON, apiProvider.toJson());
                    intent.putExtra(DetailActivity.EXTRA_ENTITY_CLASS, Service.class.getName());
                    context.startActivity(intent);
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error showing Service details: %s", e.getMessage());
                ((android.app.Activity) context).runOnUiThread(() -> {
                    ToastUtils.makeText(context, context.getString(R.string.toast_error_detail, e.getMessage()));
                });
            }
        }).start();
    }

    /**
     * Show FreerInfo details in DetailActivity
     */
    private void showCidInfoDetails(String ownerId) {
        new Thread(() -> {
            try {
                com.fc.fc_ajdk.fapi.client.FapiClient fapiClient = (com.fc.fc_ajdk.fapi.client.FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
                if (fapiClient != null) {
                    Freer freer =  fapiClient.freerById(ownerId);

                    if (freer != null) {
                        ((android.app.Activity) context).runOnUiThread(() -> {
                            Intent intent = new Intent(context, DetailActivity.class);
                            intent.putExtra(DetailActivity.EXTRA_ENTITY_JSON, freer.toJson());
                            intent.putExtra(DetailActivity.EXTRA_ENTITY_CLASS, Freer.class.getName());
                            context.startActivity(intent);
                        });
                    } else {
                        ((android.app.Activity) context).runOnUiThread(() -> {
                            ToastUtils.makeText(context, context.getString(R.string.toast_failed_load_cid_info));
                        });
                    }
                } else {
                    ((android.app.Activity) context).runOnUiThread(() -> {
                        ToastUtils.makeText(context, context.getString(R.string.toast_no_fapi_client));
                    });
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error showing CID info details: %s", e.getMessage());
                ((android.app.Activity) context).runOnUiThread(() -> {
                    ToastUtils.makeText(context, context.getString(R.string.toast_error_detail, e.getMessage()));
                });
            }
        }).start();
    }

    /**
     * Add multiple API cards to the end of the container
     * @param apiProviders List of API providers to add
     */
    public void addAllApiCard(List<ApiProvider> apiProviders) {
        if (apiProviders == null || apiProviders.isEmpty()) {
            return;
        }

        for (ApiProvider apiProvider : apiProviders) {
            int index = apiProviderList.size();
            addApiCard(apiProvider.fetchServiceType(), index, apiProvider, false);
        }
    }

    /**
     * Insert multiple API cards at a specific index in reverse order
     * For example: inserting [A,B,C] at index 1 in list [a,b,c] results in [a,C,B,A,b,c]
     * @param apiProviders List of API providers to insert
     * @param index Position to insert at
     */
    public void insertAllApiCard(List<ApiProvider> apiProviders, int index) {
        if (apiProviders == null || apiProviders.isEmpty()) {
            return;
        }

        if (index < 0 || index > apiProviderList.size()) {
            return;
        }

        // Insert in reverse order to achieve the desired result
        for (int i = apiProviders.size() - 1; i >= 0; i--) {
            ApiProvider apiProvider = apiProviders.get(i);
            View cardView = LayoutInflater.from(context).inflate(R.layout.item_api_card, apiCardsContainer, false);

            // Get views
            TextView typeTextView = cardView.findViewById(R.id.api_type);
            TextView indexTextView = cardView.findViewById(R.id.api_index);
            TextView idTextView = cardView.findViewById(R.id.api_id);
            TextView nameTextView = cardView.findViewById(R.id.api_name);
            TextView apiUrlTextView = cardView.findViewById(R.id.api_url);

            LinearLayout ownerContainer = cardView.findViewById(R.id.owner_container);
            ImageView ownerAvatarImageView = cardView.findViewById(R.id.owner_avatar);
            TextView ownerTextView = cardView.findViewById(R.id.api_owner);

            LinearLayout dealerContainer = cardView.findViewById(R.id.dealer_container);
            ImageView dealerAvatarImageView = cardView.findViewById(R.id.dealer_avatar);
            TextView dealerTextView = cardView.findViewById(R.id.api_dealer);

            LinearLayout serviceInfoContainer = cardView.findViewById(R.id.service_info_container);
            TextView birthTimeTextView = cardView.findViewById(R.id.api_birth_time);
            ImageView trateIcon = cardView.findViewById(R.id.api_rate_icon);
            TextView trateTextView = cardView.findViewById(R.id.api_rate);
            ImageView cddIcon = cardView.findViewById(R.id.api_cdd_icon);
            TextView cddTextView = cardView.findViewById(R.id.api_cdd);

            LinearLayout restContainer = cardView.findViewById(R.id.rest_container);
            TextView restTextView = cardView.findViewById(R.id.api_rest);

            ImageView converterIcon = cardView.findViewById(R.id.api_converter_icon);

            // ApiProvider now extends Service, no need for separate service variable

            // Set basic info
            Service.ServiceType type = apiProvider.fetchServiceType();
            typeTextView.setText(type != null ? type.toString() : "UNKNOWN");
            indexTextView.setText(String.format("#%d", index + 1));

            if (apiProvider.getId() != null) {
                idTextView.setText(apiProvider.getId());

                // Add click listener to show Service details
                idTextView.setOnClickListener(v -> {
                    showServiceDetails(apiProvider);
                });
            }

            if (apiProvider.getStdName() != null) {
                nameTextView.setText(apiProvider.getStdName());
            }

            if (apiProvider.getApiUrl() != null) {
                apiUrlTextView.setText(apiProvider.getApiUrl());
            }

            // Handle owner
            String owner = apiProvider.getOwner();
            if (owner != null && KeyTools.isGoodFid(owner)) {
                ownerContainer.setVisibility(View.VISIBLE);
                com.fc.freer.nobody.NobodyUi.setName(ownerTextView, owner, owner);

                Bitmap ownerAvatarBitmap = avatarManager.getAvatarBitmap(owner);
                if (ownerAvatarBitmap != null) {
                    ownerAvatarImageView.setImageBitmap(ownerAvatarBitmap);
                }

                // Add click listener to show FreerInfo details
                ownerTextView.setOnClickListener(v -> {
                    showCidInfoDetails(owner);
                });
            } else {
                ownerContainer.setVisibility(View.GONE);
            }

            // Handle dealer
            String dealerPubkey = apiProvider.getDealerPubkey();
            if (dealerPubkey != null) {
                dealerContainer.setVisibility(View.VISIBLE);

                try {
                    String dealerFid = KeyTools.pubkeyToFchAddr(dealerPubkey);
                    com.fc.freer.nobody.NobodyUi.setName(dealerTextView, dealerFid, dealerFid);

                    Bitmap dealerAvatarBitmap = avatarManager.getAvatarBitmap(dealerFid);
                    if (dealerAvatarBitmap != null) {
                        dealerAvatarImageView.setImageBitmap(dealerAvatarBitmap);
                    }
                } catch (Exception e) {
                    dealerContainer.setVisibility(View.GONE);
                }
            } else {
                dealerContainer.setVisibility(View.GONE);
            }

            // Handle service info (birth time, tRate, Cdd)
            // ApiProvider now extends Service, so call methods directly
            boolean hasAnyServiceInfo = false;

            // Handle birth time
            if (apiProvider.getBirthTime() != null) {
                try {
                    java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
                    String formattedDate = dateFormat.format(new java.util.Date(apiProvider.getBirthTime() * 1000L));
                    birthTimeTextView.setText(formattedDate);
                    birthTimeTextView.setVisibility(View.VISIBLE);
                    hasAnyServiceInfo = true;
                } catch (Exception e) {
                    birthTimeTextView.setVisibility(View.GONE);
                }
            } else {
                birthTimeTextView.setVisibility(View.GONE);
            }

            // Handle tRate
            if (apiProvider.gettRate() != null) {
                trateIcon.setVisibility(View.VISIBLE);
                trateTextView.setVisibility(View.VISIBLE);
                trateTextView.setText(String.valueOf(apiProvider.gettRate()));
                hasAnyServiceInfo = true;
            } else {
                trateIcon.setVisibility(View.GONE);
                trateTextView.setVisibility(View.GONE);
            }

            // Handle Cdd
            if (apiProvider.gettCdd() != null) {
                cddIcon.setVisibility(View.VISIBLE);
                cddTextView.setVisibility(View.VISIBLE);
                cddTextView.setText(String.valueOf(apiProvider.gettCdd()));
                hasAnyServiceInfo = true;
            } else {
                cddIcon.setVisibility(View.GONE);
                cddTextView.setVisibility(View.GONE);
            }

            serviceInfoContainer.setVisibility(hasAnyServiceInfo ? View.VISIBLE : View.GONE);

            // Handle state icon
            updateCardState(cardView, apiProvider);

            // Hide converter icon for inserted items
            converterIcon.setVisibility(View.GONE);

            // Make card clickable/selectable
            if (isSelectable) {
                cardView.setClickable(true);
                cardView.setFocusable(true);
                int position = index;
                cardView.setOnClickListener(v -> {
                    selectCard(cardView, apiProvider);
                    if (onApiCardClickListener != null) {
                        onApiCardClickListener.onApiCardClick(apiProvider, position);
                    }
                });
            }

            // Insert at the specified position
            apiCardsContainer.addView(cardView, index);
            apiProviderList.add(index, apiProvider);
            cardViews.add(index, cardView);
        }
    }

    /**
     * Sort the API cards based on a comparator
     * @param comparator The comparator to use for sorting
     */
    public void sortCards(Comparator<ApiProvider> comparator) {
        if (apiProviderList.isEmpty()) {
            return;
        }

        // Remember the currently selected provider
        ApiProvider currentlySelected = selectedApiProvider;

        // Sort the provider list
        Collections.sort(apiProviderList, comparator);

        // Clear and rebuild the UI
        apiCardsContainer.removeAllViews();
        cardViews.clear();

        // Re-add all cards in the new order
        for (int i = 0; i < apiProviderList.size(); i++) {
            ApiProvider apiProvider = apiProviderList.get(i);
            if (apiProvider == null) continue;

            View cardView = LayoutInflater.from(context).inflate(R.layout.item_api_card, apiCardsContainer, false);

            // Get views
            TextView typeTextView = cardView.findViewById(R.id.api_type);
            TextView indexTextView = cardView.findViewById(R.id.api_index);
            TextView idTextView = cardView.findViewById(R.id.api_id);
            TextView nameTextView = cardView.findViewById(R.id.api_name);
            TextView apiUrlTextView = cardView.findViewById(R.id.api_url);

            LinearLayout ownerContainer = cardView.findViewById(R.id.owner_container);
            ImageView ownerAvatarImageView = cardView.findViewById(R.id.owner_avatar);
            TextView ownerTextView = cardView.findViewById(R.id.api_owner);

            LinearLayout dealerContainer = cardView.findViewById(R.id.dealer_container);
            ImageView dealerAvatarImageView = cardView.findViewById(R.id.dealer_avatar);
            TextView dealerTextView = cardView.findViewById(R.id.api_dealer);

            TextView birthTimeTextView = cardView.findViewById(R.id.api_birth_time);
            ImageView trateIcon = cardView.findViewById(R.id.api_rate_icon);
            TextView trateTextView = cardView.findViewById(R.id.api_rate);
            ImageView cddIcon = cardView.findViewById(R.id.api_cdd_icon);
            TextView cddTextView = cardView.findViewById(R.id.api_cdd);

            LinearLayout restContainer = cardView.findViewById(R.id.rest_container);
            TextView restTextView = cardView.findViewById(R.id.api_rest);

            ImageView converterIcon = cardView.findViewById(R.id.api_converter_icon);

            // ApiProvider now extends Service, no need for separate service variable

            // Set basic info
            Service.ServiceType serviceType = apiProvider.fetchServiceType();
            typeTextView.setText(serviceType != null ? serviceType.toString() : "UNKNOWN");
            indexTextView.setText(String.format("#%d", i + 1));

            if (apiProvider.getId() != null) {
                idTextView.setText(apiProvider.getId());
                idTextView.setOnClickListener(v -> showServiceDetails(apiProvider));
            }

            if (apiProvider.getStdName() != null) {
                nameTextView.setText(apiProvider.getStdName());
            }

            if (apiProvider.getApiUrl() != null) {
                apiUrlTextView.setText(apiProvider.getApiUrl());
            }

            // Handle owner
            String owner = apiProvider.getOwner();
            if (owner != null && KeyTools.isGoodFid(owner)) {
                ownerContainer.setVisibility(View.VISIBLE);
                com.fc.freer.nobody.NobodyUi.setName(ownerTextView, owner, owner);

                Bitmap ownerAvatarBitmap = avatarManager.getAvatarBitmap(owner);
                if (ownerAvatarBitmap != null) {
                    ownerAvatarImageView.setImageBitmap(ownerAvatarBitmap);
                }

                ownerTextView.setOnClickListener(v -> showCidInfoDetails(owner));
            } else {
                ownerContainer.setVisibility(View.GONE);
            }

            // Handle dealer
            String dealerPubkey = apiProvider.getDealerPubkey();
            if (dealerPubkey != null) {
                dealerContainer.setVisibility(View.VISIBLE);

                try {
                    String dealerFid = KeyTools.pubkeyToFchAddr(dealerPubkey);
                    com.fc.freer.nobody.NobodyUi.setName(dealerTextView, dealerFid, dealerFid);

                    Bitmap dealerAvatarBitmap = avatarManager.getAvatarBitmap(dealerFid);
                    if (dealerAvatarBitmap != null) {
                        dealerAvatarImageView.setImageBitmap(dealerAvatarBitmap);
                    }
                } catch (Exception e) {
                    dealerContainer.setVisibility(View.GONE);
                }
            } else {
                dealerContainer.setVisibility(View.GONE);
            }

            // Handle service info - ApiProvider now extends Service
            if (apiProvider.getBirthTime() != null) {
                try {
                    java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
                    String formattedDate = dateFormat.format(new java.util.Date(apiProvider.getBirthTime() * 1000L));
                    birthTimeTextView.setText(formattedDate);
                    birthTimeTextView.setVisibility(View.VISIBLE);
                } catch (Exception e) {
                    birthTimeTextView.setVisibility(View.GONE);
                }
            } else {
                birthTimeTextView.setVisibility(View.GONE);
            }

            if (apiProvider.gettRate() != null) {
                trateIcon.setVisibility(View.VISIBLE);
                trateTextView.setVisibility(View.VISIBLE);
                trateTextView.setText(String.valueOf(apiProvider.gettRate()));
            } else {
                trateIcon.setVisibility(View.GONE);
                trateTextView.setVisibility(View.GONE);
            }

            if (apiProvider.gettCdd() != null) {
                cddIcon.setVisibility(View.VISIBLE);
                cddTextView.setVisibility(View.VISIBLE);
                cddTextView.setText(String.valueOf(apiProvider.gettCdd()));
            } else {
                cddIcon.setVisibility(View.GONE);
                cddTextView.setVisibility(View.GONE);
            }

            // Handle state icon
            updateCardState(cardView, apiProvider);

            // Hide converter icon
            converterIcon.setVisibility(View.GONE);

            // Make card clickable/selectable
            if (isSelectable) {
                cardView.setClickable(true);
                cardView.setFocusable(true);
                int position = i;
                cardView.setOnClickListener(v -> {
                    selectCard(cardView, apiProvider);
                    if (onApiCardClickListener != null) {
                        onApiCardClickListener.onApiCardClick(apiProvider, position);
                    }
                });
            }

            apiCardsContainer.addView(cardView);
            cardViews.add(cardView);

            // Restore selection if this was the selected provider
            if (currentlySelected != null && apiProvider.equals(currentlySelected)) {
                selectCard(cardView, apiProvider);
            }
        }
    }

    /**
     * Calculate and display the remaining requests based on balance and price
     *
     * @param apiProvider   The API provider with balance and service info
     * @param restContainer The container layout for the rest field
     * @param restTextView  The TextView to display the result
     */
    private void calculateAndDisplayRest(ApiProvider apiProvider, com.fc.fc_ajdk.fapi.client.FapiClient fapiClient, LinearLayout restContainer, TextView restTextView) {
        try {
            // ApiProvider now extends Service, so check directly
            if (fapiClient == null || apiProvider.getParams() == null) {
                restContainer.setVisibility(View.GONE);
                return;
            }

            Long balance = fapiClient.getLastBalance();
            if (balance == null) {
                restContainer.setVisibility(View.GONE);
                return;
            }

            // Get price per KB from apiProvider (which is now a Service)
            String pricePerKBytesStr = apiProvider.getPricePerKB();

            if (pricePerKBytesStr == null || pricePerKBytesStr.isEmpty()) {
                restContainer.setVisibility(View.GONE);
                return;
            }

            // Convert price from FCH string to satoshi
            Long pricePerKBInSatoshi = FchUtils.coinStrToSatoshi(pricePerKBytesStr);

            if (pricePerKBInSatoshi == null || pricePerKBInSatoshi == 0) {
                // Free service or invalid price
                restContainer.setVisibility(View.VISIBLE);
                restTextView.setText("∞ " + context.getString(R.string.kb));
                return;
            }

            // Calculate remaining KB: balance / pricePerKB
            long remainingKB = balance / pricePerKBInSatoshi;

            // Display the result
            restContainer.setVisibility(View.VISIBLE);
            restTextView.setText(String.format("%,d %s", remainingKB, context.getString(R.string.kb)));

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error calculating rest: %s", e.getMessage());
            restContainer.setVisibility(View.GONE);
        }
    }

    /**
     * Add an empty API card placeholder with a plus icon
     * @param serviceType The service type for this empty slot
     * @param index The account index
     */
    public void addEmptyApiCard(Service.ServiceType serviceType, int index) {
        View cardView = LayoutInflater.from(context).inflate(R.layout.item_api_card, apiCardsContainer, false);

        // Apply border background to empty card
        cardView.setBackgroundResource(R.drawable.container_outline);

        // Get views
        TextView typeTextView = cardView.findViewById(R.id.api_type);
        TextView indexTextView = cardView.findViewById(R.id.api_index);
        TextView idTextView = cardView.findViewById(R.id.api_id);
        TextView idLabelTextView = cardView.findViewById(R.id.api_id_label);
        TextView nameTextView = cardView.findViewById(R.id.api_name);
        TextView apiUrlTextView = cardView.findViewById(R.id.api_url);
        TextView apiUrlLabelTextView = cardView.findViewById(R.id.api_url_label);

        LinearLayout ownerContainer = cardView.findViewById(R.id.owner_container);
        LinearLayout dealerContainer = cardView.findViewById(R.id.dealer_container);
        LinearLayout serviceInfoContainer = cardView.findViewById(R.id.service_info_container);
        LinearLayout restContainer = cardView.findViewById(R.id.rest_container);
        ImageView stateIcon = cardView.findViewById(R.id.api_state_icon);
        ImageView converterIcon = cardView.findViewById(R.id.api_converter_icon);
        TextView birthTimeTextView = cardView.findViewById(R.id.api_birth_time);

        // Set basic info
        typeTextView.setText(serviceType != null ? serviceType.toString() : "UNKNOWN");
        indexTextView.setText(String.format("#%d", index + 1));

        // Set placeholder text to indicate it's an empty slot
        idTextView.setText("");
        nameTextView.setText(context.getString(R.string.click_plus_to_add));
        nameTextView.setTextColor(context.getResources().getColor(R.color.hint, null));
        apiUrlTextView.setText("");

        // Hide ID and API URL labels
        idLabelTextView.setVisibility(View.GONE);
        idTextView.setVisibility(View.GONE);
        apiUrlLabelTextView.setVisibility(View.GONE);
        apiUrlTextView.setVisibility(View.GONE);

        // Hide owner and dealer containers
        ownerContainer.setVisibility(View.GONE);
        dealerContainer.setVisibility(View.GONE);

        // Keep service info container visible but empty to maintain layout spacing
        // This ensures the plus icon stays at the right end
        if (serviceInfoContainer != null) {
            serviceInfoContainer.setVisibility(View.VISIBLE);
            // Hide all children of service info container
            if (birthTimeTextView != null) {
                birthTimeTextView.setVisibility(View.GONE);
            }
            ImageView rateIcon = cardView.findViewById(R.id.api_rate_icon);
            TextView rateTextView = cardView.findViewById(R.id.api_rate);
            ImageView cddIcon = cardView.findViewById(R.id.api_cdd_icon);
            TextView cddTextView = cardView.findViewById(R.id.api_cdd);
            if (rateIcon != null) rateIcon.setVisibility(View.GONE);
            if (rateTextView != null) rateTextView.setVisibility(View.GONE);
            if (cddIcon != null) cddIcon.setVisibility(View.GONE);
            if (cddTextView != null) cddTextView.setVisibility(View.GONE);
        }

        if (restContainer != null) {
            restContainer.setVisibility(View.GONE);
        }

        // Hide state icon
        stateIcon.setVisibility(View.GONE);

        // Show plus icon in the bottom right corner
        converterIcon.setVisibility(View.VISIBLE);
        converterIcon.setImageResource(R.drawable.ic_plus);

        converterIcon.setOnClickListener(v -> {
            if (onConverterClickListener != null) {
                onConverterClickListener.onConverterClick(null, serviceType, index);
            }
        });

        apiCardsContainer.addView(cardView);
        apiProviderList.add(null); // Add null to maintain index alignment
        cardViews.add(cardView);
    }
}
