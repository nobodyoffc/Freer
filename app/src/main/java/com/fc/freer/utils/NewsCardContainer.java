package com.fc.freer.utils;

import static android.view.View.GONE;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.PopupMenu;
import android.widget.ImageView;

import com.fc.fc_ajdk.data.fcData.News;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.utils.DateUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.AvatarManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class NewsCardContainer {
    private static final String TAG = "NewsCardContainer";
    private final Context context;
    private final ViewGroup newsListContainer;
    private final List<News> newsList;
    private final List<CompoundButton> checkBoxes;
    private final ChooseMode chooseMode;
    private OnNewsListChangedListener onNewsListChangedListener;
    private final List<String> menuItems;
    private OnMenuItemClickListener onMenuItemClickListener;
    private OnNewsClickListener onNewsClickListener;
    private OnNewsRemoveListener onNewsRemoveListener;
    private android.widget.ScrollView scrollView;

    public interface OnNewsListChangedListener {
        void onNewsListChanged(List<News> updatedNewsList);
    }

    public interface OnMenuItemClickListener {
        void onMenuItemClick(String menuItem, News news);
    }

    public interface OnNewsClickListener {
        void onNewsClick(News news);
    }

    public interface OnNewsRemoveListener {
        void onNewsRemove(News news);
    }

    public NewsCardContainer(Context context, LinearLayout newsListContainer, ChooseMode chooseMode) {
        this(context, newsListContainer, chooseMode, List.of(context.getString(R.string.add_doer_to_list)));
    }

    public NewsCardContainer(Context context, LinearLayout newsListContainer, ChooseMode chooseMode, List<String> menuItems) {
        this.context = context;
        this.newsListContainer = newsListContainer;
        this.newsList = new ArrayList<>();
        this.checkBoxes = new ArrayList<>();
        this.chooseMode = chooseMode;
        this.menuItems = menuItems;
    }

    public void setOnNewsListChangedListener(OnNewsListChangedListener listener) {
        this.onNewsListChangedListener = listener;
    }

    public void setOnMenuItemClickListener(OnMenuItemClickListener listener) {
        this.onMenuItemClickListener = listener;
    }

    public void setOnNewsClickListener(OnNewsClickListener listener) {
        this.onNewsClickListener = listener;
    }

    public void setOnNewsRemoveListener(OnNewsRemoveListener listener) {
        this.onNewsRemoveListener = listener;
    }

    public void setScrollView(android.widget.ScrollView scrollView) {
        this.scrollView = scrollView;
    }

    private void notifyNewsListChanged() {
        if (onNewsListChangedListener != null) {
            onNewsListChangedListener.onNewsListChanged(new ArrayList<>(newsList));
        }
    }

    public void addNewsCard(News news, Map<String, String> cidMap) {
        addNewsCardAtPosition(news, newsList.size(),cidMap);
    }

    private void showNewsDetailActivity(News news) {
        android.content.Intent intent = new android.content.Intent(context, com.fc.freer.ui.DetailActivity.class);
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_JSON, news.toJson());
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_CLASS, News.class.getName());
        context.startActivity(intent);
    }

    public List<News> getSelectedNews() {
        List<News> selectedNews = new ArrayList<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (checkBoxes.get(i).isChecked()) {
                selectedNews.add(newsList.get(i));
            }
        }
        return selectedNews;
    }

    public void clearAll() {
        newsList.clear();
        newsListContainer.removeAllViews();
        checkBoxes.clear();
    }

    public List<News> getNewsList() {
        return newsList;
    }

    public void selectAll(boolean selected) {
        if (chooseMode != ChooseMode.CHOOSE_MULTI) {
            return; // Do nothing for non-checkbox modes or single choice mode
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

    /**
     * Removes all currently selected news from the container
     */
    public void removeSelectedNews() {
        if (chooseMode != ChooseMode.CHOOSE_MULTI || checkBoxes.isEmpty()) {
            return;
        }

        List<Integer> selectedIndices = getSelectedIndices();
        removeItemsAtIndices(selectedIndices);
        notifyNewsListChanged();
    }

    private List<Integer> getSelectedIndices() {
        List<Integer> selectedIndices = new ArrayList<>();
        for (int i = checkBoxes.size() - 1; i >= 0; i--) {
            if (checkBoxes.get(i).isChecked()) {
                selectedIndices.add(i);
            }
        }
        return selectedIndices;
    }

    private void removeItemsAtIndices(List<Integer> indices) {
        for (int index : indices) {
            if (index < newsListContainer.getChildCount()) {
                newsListContainer.removeViewAt(index);
            }
            if (index < newsList.size()) {
                newsList.remove(index);
            }
            if (index < checkBoxes.size()) {
                checkBoxes.remove(index);
            }
        }
    }

    /**
     * Removes cards from the beginning of the list (newest/latest cards)
     * @param count Number of cards to remove from the beginning
     */
    public void removeFromBeginning(int count) {
        if (count <= 0 || count > newsList.size()) {
            return;
        }

        removeViewsFromBeginning(count);
        removeDataFromBeginning(count);
        notifyNewsListChanged();
    }

    /**
     * Removes cards from the end of the list (oldest/earliest cards)
     * @param count Number of cards to remove from the end
     */
    public void removeFromEnd(int count) {
        if (count <= 0 || count > newsList.size()) {
            return;
        }

        removeViewsFromEnd(count);
        removeDataFromEnd(count);
        notifyNewsListChanged();
    }

    private void removeViewsFromBeginning(int count) {
        for (int i = 0; i < count; i++) {
            newsListContainer.removeViewAt(0);
        }
    }

    private void removeDataFromBeginning(int count) {
        for (int i = 0; i < count; i++) {
            newsList.remove(0);
            if (!checkBoxes.isEmpty()) {
                checkBoxes.remove(0);
            }
        }
    }

    private void removeViewsFromEnd(int count) {
        for (int i = 0; i < count; i++) {
            newsListContainer.removeViewAt(newsListContainer.getChildCount() - 1);
        }
    }

    private void removeDataFromEnd(int count) {
        int size = newsList.size();
        for (int i = 0; i < count; i++) {
            int index = size - 1 - i;
            newsList.remove(index);
            if (checkBoxes.size() > index) {
                checkBoxes.remove(index);
            }
        }
    }

    /**
     * Adds news cards to the beginning of the list (newest/latest position) while removing
     * the same number from the end to maintain container size. Adjusts scroll position so
     * user stays viewing the same content and can scroll up to see new cards one by one.
     *
     * @param newsToAdd List of news objects to add at the beginning
     * @param cidMap Map of FID to CID for display names
     * @param maxContainerSize Maximum allowed container size
     */
    public void addNewsCardsToBeginningWithBottomRemoval(List<News> newsToAdd, Map<String, String> cidMap, int maxContainerSize) {
        if (newsToAdd == null || newsToAdd.isEmpty()) {
            return;
        }

        int currentSize = newsList.size();
        int newItemsCount = newsToAdd.size();

        // Calculate how many items to remove from the bottom
        int itemsToRemove = Math.max(0, currentSize + newItemsCount - maxContainerSize);

        // Clear all old boundary indicators before setting a new one
        clearAllBoundaryIndicators();

        if (itemsToRemove > 0) {
            // Save the current scroll position
            int savedScrollY = scrollView != null ? scrollView.getScrollY() : 0;

            // Remove items from the end (bottom)
            removeFromEnd(itemsToRemove);

            // Add new items to the beginning in reverse order to maintain correct order
            for (int i = newsToAdd.size() - 1; i >= 0; i--) {
                News news = newsToAdd.get(i);
                addNewsCardAtPosition(news, 0, cidMap);
            }

            // Mark the boundary card (last new card added, which is at index newItemsCount - 1)
            setBoundaryIndicator(newItemsCount - 1, true);

            // Calculate the height of newly added cards to adjust scroll position
            if (scrollView != null) {
                scrollView.post(() -> {
                    int addedHeight = 0;
                    for (int i = 0; i < newItemsCount && i < newsListContainer.getChildCount(); i++) {
                        View cardView = newsListContainer.getChildAt(i);
                        addedHeight += cardView.getHeight();
                    }

                    // Adjust scroll position to account for the new cards added at the top
                    // This keeps the user viewing the same content they were looking at
                    int newScrollY = savedScrollY + addedHeight;
                    scrollView.scrollTo(0, newScrollY);

                    TimberLogger.d(TAG, "Added %d cards to beginning, adjusted scroll from %d to %d (added height: %d)",
                        newItemsCount, savedScrollY, newScrollY, addedHeight);
                });
            }

            TimberLogger.d(TAG, "Added %d cards to beginning, removed %d from bottom, container size: %d",
                newItemsCount, itemsToRemove, newsList.size());
        } else {
            // Container not full, save scroll position before adding
            int savedScrollY = scrollView != null ? scrollView.getScrollY() : 0;

            // Add new items to the beginning
            for (int i = newsToAdd.size() - 1; i >= 0; i--) {
                News news = newsToAdd.get(i);
                addNewsCardAtPosition(news, 0, cidMap);
            }

            // Mark the boundary card (last new card added, which is at index newItemsCount - 1)
            setBoundaryIndicator(newItemsCount - 1, true);

            // Adjust scroll position for the newly added cards
            if (scrollView != null) {
                scrollView.post(() -> {
                    int addedHeight = 0;
                    for (int i = 0; i < newItemsCount && i < newsListContainer.getChildCount(); i++) {
                        View cardView = newsListContainer.getChildAt(i);
                        addedHeight += cardView.getHeight();
                    }

                    int newScrollY = savedScrollY + addedHeight;
                    scrollView.scrollTo(0, newScrollY);

                    TimberLogger.d(TAG, "Container not full - adjusted scroll from %d to %d", savedScrollY, newScrollY);
                });
            }

            TimberLogger.d(TAG, "Added %d cards to beginning, container size: %d/%d",
                newItemsCount, newsList.size(), maxContainerSize);
        }

        // Notify that the list has changed
        notifyNewsListChanged();
    }

    /**
     * Adds news cards to the end of the list (oldest position) while removing the same number
     * from the beginning to maintain container size. Preserves scroll position so user stays
     * at the same visual location and can scroll down to see new cards one by one.
     *
     * @param newsToAdd List of news objects to add at the end
     * @param cidMap Map of FID to CID for display names
     * @param maxContainerSize Maximum allowed container size
     */
    public void addNewsCardsToEndWithTopRemoval(List<News> newsToAdd, Map<String, String> cidMap, int maxContainerSize) {
        if (newsToAdd == null || newsToAdd.isEmpty()) {
            return;
        }

        int currentSize = newsList.size();
        int newItemsCount = newsToAdd.size();

        // Calculate how many items to remove from the top
        int itemsToRemove = Math.max(0, currentSize + newItemsCount - maxContainerSize);

        // The boundary card will be the first new card added at the current end
        int boundaryIndex = currentSize - itemsToRemove;

        // Clear all old boundary indicators before setting a new one
        clearAllBoundaryIndicators();

        if (itemsToRemove > 0) {
            // Save current scroll position
            final int savedScrollY = scrollView != null ? scrollView.getScrollY() : 0;

            // Calculate the height of items that will be removed
            int removedHeight = 0;
            for (int i = 0; i < itemsToRemove && i < newsListContainer.getChildCount(); i++) {
                View cardView = newsListContainer.getChildAt(i);
                removedHeight += cardView.getHeight();
            }
            final int finalRemovedHeight = removedHeight;

            // Remove items from the beginning (top)
            removeFromBeginning(itemsToRemove);

            // Adjust scroll position immediately after removing items to prevent jumping
            if (scrollView != null && finalRemovedHeight > 0) {
                int newScrollY = Math.max(0, savedScrollY - finalRemovedHeight);
                scrollView.scrollTo(0, newScrollY);
                TimberLogger.d(TAG, "Adjusted scroll after removal: %d -> %d (removed height: %d)",
                    savedScrollY, newScrollY, finalRemovedHeight);
            }

            // Add new items to the end
            for (News news : newsToAdd) {
                addNewsCard(news, cidMap);
            }

            // Mark the boundary card (first new card, which is now at boundaryIndex)
            setBoundaryIndicator(boundaryIndex, true);

            TimberLogger.d(TAG, "Added %d cards to end, removed %d from top, container size: %d",
                newItemsCount, itemsToRemove, newsList.size());
        } else {
            // Container not full, just add to the end
            for (News news : newsToAdd) {
                addNewsCard(news, cidMap);
            }

            // Mark the boundary card (first new card at the current end)
            setBoundaryIndicator(boundaryIndex, true);

            TimberLogger.d(TAG, "Added %d cards to end, container size: %d/%d",
                newItemsCount, newsList.size(), maxContainerSize);
        }

        // Notify that the list has changed
        notifyNewsListChanged();
    }

    public void sortByDoer(boolean ascending, boolean enableSort) {
        sortNews(enableSort, (pair1, pair2) -> {
            String doer1 = pair1.news.getDoer();
            String doer2 = pair2.news.getDoer();
            return compareNullable(doer1, doer2, ascending);
        });
    }

    public void sortByObjectType(boolean ascending, boolean enableSort) {
        sortNews(enableSort, (pair1, pair2) -> {
            String type1 = pair1.news.getObjectType();
            String type2 = pair2.news.getObjectType();
            return compareNullable(type1, type2, ascending);
        });
    }

    public void sortByObjectName(boolean ascending, boolean enableSort) {
        sortNews(enableSort, (pair1, pair2) -> {
            String name1 = pair1.news.getObjectName();
            String name2 = pair2.news.getObjectName();
            return compareNullable(name1, name2, ascending);
        });
    }

    public void sortByTime(boolean ascending, boolean enableSort) {
        sortNews(enableSort, (pair1, pair2) -> {
            Long time1 = pair1.news.getTime();
            Long time2 = pair2.news.getTime();
            return compareNullable(time1, time2, ascending);
        });
    }

    private void addNewsCardAtPosition(News news, int position, Map<String, String> cidMap) {
        View cardView = createCardView();
        CompoundButton checkBox = setupCardViewInteractions(cardView, news,cidMap);

        newsListContainer.addView(cardView, position);
        newsList.add(position, news);
        if (checkBox != null) {
            checkBoxes.add(position, checkBox);
        }
    }

    private View createCardView() {
        int layoutResId = getLayoutResId();
        return LayoutInflater.from(context).inflate(layoutResId, newsListContainer, false);
    }

    private int getLayoutResId() {
        return R.layout.item_news_card;
    }

    private CompoundButton setupCardViewInteractions(View cardView, News news, Map<String, String> cidMap) {
        CompoundButton checkBox = setupCheckBox(cardView);
        setupCardData(cardView, news,cidMap);
        setupClickListeners(cardView, news);
        return checkBox;
    }

    private CompoundButton setupCheckBox(View cardView) {
        if (chooseMode == ChooseMode.CHOOSE_ONE_RETURN || chooseMode == ChooseMode.WITHOUT_CHOOSE) {
            return null;
        }

        CompoundButton checkBox = cardView.findViewById(R.id.news_checkbox);
        if (chooseMode == ChooseMode.CHOOSE_ONE) {
            checkBox.setOnCheckedChangeListener(createSingleChoiceListener(checkBox));
        } else {
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> notifyNewsListChanged());
        }
        return checkBox;
    }

    private CompoundButton.OnCheckedChangeListener createSingleChoiceListener(CompoundButton checkBox) {
        return (buttonView, isChecked) -> {
            if (isChecked) {
                uncheckAllExcept(checkBox);
            }
        };
    }

    private void uncheckAllExcept(CompoundButton selectedCheckBox) {
        for (CompoundButton cb : checkBoxes) {
            if (cb != selectedCheckBox) {
                cb.setChecked(false);
            }
        }
    }

    private void setupCardData(View cardView, News news, Map<String, String> cidMap) {
        ImageView avatarView = cardView.findViewById(R.id.news_avatar);
        TextView doerValue = cardView.findViewById(R.id.news_doer_value);
        TextView timeValue = cardView.findViewById(R.id.news_time_value);
        TextView actValue = cardView.findViewById(R.id.news_act_value);
        TextView objectTypeValue = cardView.findViewById(R.id.news_object_type_value);
        TextView objectNameValue = cardView.findViewById(R.id.news_object_name_value);
        TextView objectBriefValue = cardView.findViewById(R.id.news_object_brief_value);
        View newIndicator = cardView.findViewById(R.id.new_indicator);

        setupNewIndicator(newIndicator, news);
        setupAvatar(avatarView, news.getDoer());
        setDoerText(doerValue, news.getDoer(), cidMap);
        setTimeValue(timeValue, news.getTime());
        setTextValue(actValue, news.getAct());
        setTextValue(objectTypeValue, news.getObjectType());
        setTextValue(objectNameValue, news.getObjectName());
        setObjectBrief(objectBriefValue, news.getObjectBrief());
    }

    private void setupNewIndicator(View newIndicator, News news) {
        if (newIndicator != null && Boolean.TRUE.equals(news.getNew())) {
            newIndicator.setVisibility(View.VISIBLE);
            news.setNew(Boolean.FALSE);
        }
    }

    private void setDoerText(TextView doerValue, String doer, Map<String, String> cidMap) {
        String displayName = (cidMap != null && cidMap.containsKey(doer)) ? cidMap.get(doer) : doer;
        setTextValue(doerValue, displayName);
    }

    private void setObjectBrief(TextView objectBriefValue, String objectBrief) {
        if (objectBrief == null || objectBrief.isEmpty()) {
            objectBriefValue.setVisibility(GONE);
        } else {
            setTextValue(objectBriefValue, objectBrief);
        }
    }

    private void setupAvatar(ImageView avatarView, String fid) {
        if (fid == null || fid.isEmpty()) {
            avatarView.setImageResource(R.drawable.container_outline);
            return;
        }

        try {
            AvatarManager avatarManager = AvatarManager.getInstance(context);
            Bitmap avatarBitmap = avatarManager.getAvatarBitmap(fid);
            avatarView.setImageBitmap(avatarBitmap);
            if (avatarBitmap == null) {
                avatarView.setImageResource(R.drawable.container_outline);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to load avatar for FID %s: %s", fid, e.getMessage());
            avatarView.setImageResource(R.drawable.container_outline);
        }
    }

    private void setTextValue(TextView textView, String value) {
        textView.setText(value != null ? value : "");
    }

    private void setTimeValue(TextView textView, Long time) {
        String timeStr = (time != null) ? DateUtils.longShortToTime(time, DateUtils.TO_MINUTE) : "";
        textView.setText(timeStr);
    }

    private void setupClickListeners(View cardView, News news) {
        View.OnClickListener clickListener = createClickListener(news);
        View.OnLongClickListener longPressListener = createLongPressListener(cardView, news);

        cardView.setOnClickListener(clickListener);
        cardView.setOnLongClickListener(longPressListener);

        TextView doerValue = cardView.findViewById(R.id.news_doer_value);
        if (doerValue != null) {
            doerValue.setOnClickListener(clickListener);
            doerValue.setOnLongClickListener(longPressListener);
        }

        TextView objectNameValue = cardView.findViewById(R.id.news_object_name_value);
        if (objectNameValue != null) {
            objectNameValue.setOnClickListener(clickListener);
            objectNameValue.setOnLongClickListener(longPressListener);
        }
    }

    private View.OnClickListener createClickListener(News news) {
        return v -> {
            if (onNewsClickListener != null) {
                onNewsClickListener.onNewsClick(news);
            } else {
                showNewsDetailActivity(news);
            }
        };
    }

    private View.OnLongClickListener createLongPressListener(View cardView, News news) {
        return v -> {
            if (menuItems == null || menuItems.isEmpty()) {
                return false;
            }

            PopupMenu popup = new PopupMenu(context, v);
            for (String menuItem : menuItems) {
                popup.getMenu().add(menuItem);
            }

            popup.setOnMenuItemClickListener(item -> handleMenuItemClick(item, cardView, news));
            popup.show();
            return true;
        };
    }

    private boolean handleMenuItemClick(android.view.MenuItem item, View cardView, News news) {
        if (item.getTitle() == null) {
            return false;
        }

        String title = item.getTitle().toString();
        if ("Delete".equals(title)) {
            removeNewsCard(cardView, news);
            return true;
        } else if(context.getString(R.string.add_doer_to_list).equals(title)){
            FreerApplication.addFid(news.getDoer());
            ToastUtils.makeText(context,context.getString(R.string.added_to_fid_list));
        } else if (onMenuItemClickListener != null) {
            onMenuItemClickListener.onMenuItemClick(title, news);
            return true;
        }
        return false;
    }

    private void removeNewsCard(View cardView, News news) {
        newsListContainer.removeView(cardView);
        int index = newsList.indexOf(news);
        if (index != -1) {
            newsList.remove(index);
            if (index < checkBoxes.size()) {
                checkBoxes.remove(index);
            }
        }
        notifyNewsListChanged();
    }

    private void sortNews(boolean enableSort, Comparator<NewsViewPair> comparator) {
        if (newsList.isEmpty() || !enableSort) {
            return;
        }

        List<NewsViewPair> pairs = createNewsViewPairs();
        pairs.sort(comparator);
        updateListsFromPairs(pairs);
    }

    private List<NewsViewPair> createNewsViewPairs() {
        List<NewsViewPair> pairs = new ArrayList<>();
        for (int i = 0; i < newsList.size(); i++) {
            News news = newsList.get(i);
            View cardView = newsListContainer.getChildAt(i);
            CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
            pairs.add(new NewsViewPair(news, cardView, checkBox));
        }
        return pairs;
    }

    private void updateListsFromPairs(List<NewsViewPair> pairs) {
        newsList.clear();
        checkBoxes.clear();
        newsListContainer.removeAllViews();

        for (NewsViewPair pair : pairs) {
            newsList.add(pair.news);
            if (pair.checkBox != null) {
                checkBoxes.add(pair.checkBox);
            }
            newsListContainer.addView(pair.cardView);
        }
    }

    private <T extends Comparable<T>> int compareNullable(T value1, T value2, boolean ascending) {
        if (value1 == null && value2 == null) return 0;
        if (value1 == null) return 1;
        if (value2 == null) return -1;
        return ascending ? value1.compareTo(value2) : value2.compareTo(value1);
    }

    /**
     * Sets or clears the boundary indicator for a specific card at the given index.
     * The boundary indicator is shown in warning color to mark the boundary between
     * old and new content when loading more cards.
     *
     * @param index The index of the card in the container
     * @param show Whether to show or hide the boundary indicator
     */
    public void setBoundaryIndicator(int index, boolean show) {
        if (index < 0 || index >= newsListContainer.getChildCount()) {
            return;
        }

        View cardView = newsListContainer.getChildAt(index);
        if (cardView == null) {
            return;
        }

        View boundaryIndicator = cardView.findViewById(R.id.new_indicator);
        if (boundaryIndicator != null && index < newsList.size()) {
            News news = newsList.get(index);

            if (show) {
                // Show boundary indicator in warning color
                boundaryIndicator.setVisibility(View.VISIBLE);
                boundaryIndicator.setBackgroundColor(context.getResources().getColor(R.color.warning, null));
            } else {
                // Clear boundary indicator - restore based on news.getNew()
                if (Boolean.TRUE.equals(news.getNew())) {
                    boundaryIndicator.setVisibility(View.VISIBLE);
                    // Restore original new indicator color (you may need to adjust this color)
                    boundaryIndicator.setBackgroundColor(context.getResources().getColor(R.color.accent, null));
                } else {
                    boundaryIndicator.setVisibility(View.GONE);
                }
            }
        }
    }

    /**
     * Clears all boundary indicators from all cards
     */
    public void clearAllBoundaryIndicators() {
        for (int i = 0; i < newsListContainer.getChildCount(); i++) {
            setBoundaryIndicator(i, false);
        }
    }

    private record NewsViewPair(News news, View cardView, CompoundButton checkBox) {
    }
}
