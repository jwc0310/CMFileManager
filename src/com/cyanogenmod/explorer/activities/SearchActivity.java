/*
 * Copyright (C) 2012 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cyanogenmod.explorer.activities;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.SearchRecentSuggestions;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.cyanogenmod.explorer.ExplorerApplication;
import com.cyanogenmod.explorer.R;
import com.cyanogenmod.explorer.adapters.SearchResultAdapter;
import com.cyanogenmod.explorer.commands.AsyncResultExecutable;
import com.cyanogenmod.explorer.commands.AsyncResultListener;
import com.cyanogenmod.explorer.model.Directory;
import com.cyanogenmod.explorer.model.FileSystemObject;
import com.cyanogenmod.explorer.model.ParentDirectory;
import com.cyanogenmod.explorer.model.Query;
import com.cyanogenmod.explorer.model.SearchResult;
import com.cyanogenmod.explorer.model.Symlink;
import com.cyanogenmod.explorer.parcelables.SearchInfoParcelable;
import com.cyanogenmod.explorer.preferences.Preferences;
import com.cyanogenmod.explorer.providers.RecentSearchesContentProvider;
import com.cyanogenmod.explorer.tasks.SearchResultDrawingAsyncTask;
import com.cyanogenmod.explorer.ui.dialogs.MessageProgressDialog;
import com.cyanogenmod.explorer.util.CommandHelper;
import com.cyanogenmod.explorer.util.DialogHelper;
import com.cyanogenmod.explorer.util.ExceptionUtil;
import com.cyanogenmod.explorer.util.FileHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * An activity for search files and folders.
 */
public class SearchActivity extends Activity implements AsyncResultListener, OnItemClickListener {

    private static final String TAG = "SearchActivity"; //$NON-NLS-1$

    /**
     * An {@link Intent} action for restore view information.
     */
    public static final String ACTION_RESTORE =
            "com.cyanogenmod.explorer.activities.SearchActivity#Restore"; //$NON-NLS-1$

    /**
     * Intent extra parameter for search in the selected directory on enter.
     */
    public static final String EXTRA_SEARCH_DIRECTORY = "extra_search_directory";  //$NON-NLS-1$

    /**
     * Intent extra parameter for pass the restore information.
     */
    public static final String EXTRA_SEARCH_RESTORE = "extra_search_restore";  //$NON-NLS-1$


    //Minimum characters to allow query
    private static final int MIN_CHARS_SEARCH = 3;


    private MessageProgressDialog mDialog = null;
    private AsyncResultExecutable mExecutable = null;

    private ListView mSearchListView;
    private ProgressBar mSearchWaiting;
    private TextView mSearchFoundItems;
    private TextView mSearchTerms;
    private View mEmptyListMsg;
    private SearchResultAdapter mAdapter;

    private String mSearchDirectory;
    private List<FileSystemObject> mResultList;
    private Query mQuery;

    private SearchInfoParcelable mRestoreState;

    private SearchResultDrawingAsyncTask mDrawingSearchResultTask;


    /**
     * {@inheritDoc}
     */
    @Override
    protected void onCreate(Bundle state) {
        if (ExplorerApplication.DEBUG) {
            Log.d(TAG, "NavigationActivity.onCreate"); //$NON-NLS-1$
        }

        //Request features
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        //Set in transition
        overridePendingTransition(R.anim.translate_to_right_in, R.anim.hold_out);

        //Set the main layout of the activity
        setContentView(R.layout.search);

        //Restore state
        if (state != null) {
            restoreState(state);
        }

        //Initialize action bars and search
        initTitleActionBar();
        initComponents();
        if (this.mRestoreState != null) {
            //Restore activity from cached data
            loadFromCacheData();
        } else {
            //New query
            if (Intent.ACTION_SEARCH.equals(getIntent().getAction())) {
                initSearch();
            } else if (ACTION_RESTORE.equals(getIntent().getAction())) {
                restoreState(getIntent().getExtras());
                loadFromCacheData();
            }
        }

        //Save state
        super.onCreate(state);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onNewIntent(Intent intent) {
        //New query
        if (Intent.ACTION_SEARCH.equals(getIntent().getAction())) {
            initSearch();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onPause() {
        //Set out transition
        overridePendingTransition(R.anim.hold_in, R.anim.translate_to_left_out);
        super.onPause();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (ExplorerApplication.DEBUG) {
            Log.d(TAG, "SearchActivity.onSaveInstanceState"); //$NON-NLS-1$
        }
        saveState(outState);
        super.onSaveInstanceState(outState);
    }

    /**
     * Method that save the instance of the activity.
     *
     * @param state The current state of the activity
     */
    private void saveState(Bundle state) {
        try {
            if (this.mAdapter != null) {
                state.putParcelable(EXTRA_SEARCH_RESTORE, createSearchInfo());
            }
        } catch (Throwable ex) {
            Log.w(TAG, "The state can't be saved", ex); //$NON-NLS-1$
        }
    }

    /**
     * Method that restore the instance of the activity.
     *
     * @param state The previous state of the activity
     */
    private void restoreState(Bundle state) {
        try {
            if (state.containsKey(EXTRA_SEARCH_RESTORE)) {
                this.mRestoreState = state.getParcelable(EXTRA_SEARCH_RESTORE);
            }
        } catch (Throwable ex) {
            Log.w(TAG, "The state can't be restored", ex); //$NON-NLS-1$
        }
    }

    /**
     * Method that initializes the titlebar of the activity.
     */
    private void initTitleActionBar() {
        //Configure the action bar options
        getActionBar().setBackgroundDrawable(
                getResources().getDrawable(R.drawable.bg_holo_titlebar));
        getActionBar().setDisplayOptions(
                ActionBar.DISPLAY_SHOW_CUSTOM | ActionBar.DISPLAY_SHOW_HOME);
        getActionBar().setDisplayHomeAsUpEnabled(true);
        View customTitle = getLayoutInflater().inflate(R.layout.simple_customtitle, null, false);
        TextView title = (TextView)customTitle.findViewById(R.id.customtitle_title);
        title.setText(R.string.search);
        title.setContentDescription(getString(R.string.search));
        getActionBar().setCustomView(customTitle);
    }

    /**
     * Method that initializes the component of the activity.
     */
    private void initComponents() {
        //Empty list view
        this.mEmptyListMsg = findViewById(R.id.search_empty_msg);
        //The list view
        this.mSearchListView = (ListView)findViewById(R.id.search_listview);
        this.mSearchListView.setOnItemClickListener(this);
        //Other components
        this.mSearchWaiting = (ProgressBar)findViewById(R.id.search_waiting);
        this.mSearchFoundItems = (TextView)findViewById(R.id.search_status_found_items);
        setFoundItems(0, ""); //$NON-NLS-1$
        this.mSearchTerms = (TextView)findViewById(R.id.search_status_query_terms);
        this.mSearchTerms.setText(
                Html.fromHtml(getString(R.string.search_terms, ""))); //$NON-NLS-1$
    }

    /**
     * Method that initializes the titlebar of the activity.
     */
    private void initSearch() {
        //Stop any pending action
        try {
            if (SearchActivity.this.mDrawingSearchResultTask != null
                    && SearchActivity.this.mDrawingSearchResultTask.isRunning()) {
                SearchActivity.this.mDrawingSearchResultTask.cancel(true);
            }
        } catch (Throwable ex2) {
            /**NON BLOCK**/
        }
        try {
            if (SearchActivity.this.mDialog != null) {
                SearchActivity.this.mDialog.dismiss();
            }
        } catch (Throwable ex2) {
            /**NON BLOCK**/
        }

        //Recovery the search directory
        Bundle bundle = getIntent().getBundleExtra(SearchManager.APP_DATA);
        //If data is not present, use root directory to do the search
        this.mSearchDirectory = FileHelper.ROOT_DIRECTORY;
        if (bundle != null) {
            this.mSearchDirectory =
                    bundle.getString(EXTRA_SEARCH_DIRECTORY, FileHelper.ROOT_DIRECTORY);
        }

        //Retrieve the query ¿from voice recognizer?
        boolean voiceQuery = true;
        List<String> userQueries =
                getIntent().getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (userQueries == null || userQueries.size() == 0) {
            //From input text
            userQueries = new ArrayList<String>();
            //Recovers and save the last term search in the memory
            Preferences.setLastSearch(getIntent().getStringExtra(SearchManager.QUERY));
            userQueries.add(Preferences.getLastSearch());
            voiceQuery = false;
        }

        //Filter the queries? Needed if queries come from voice recognition
        final List<String> filteredUserQueries =
                (voiceQuery) ? filterQuery(userQueries) : userQueries;

        //Create the queries
        this.mQuery = new Query().fillSlots(filteredUserQueries);
        List<String> queries = this.mQuery.getQueries();

        //Check if some queries has lower than allowed, in this case
        //request the user for stop the search
        boolean ask = false;
        for (int i = 0; i < queries.size(); i++) {
            if (queries.get(i).trim().length() < MIN_CHARS_SEARCH) {
                ask = true;
                break;
            }
        }
        if (ask) {
            askUserBeforeSearch(voiceQuery, this.mQuery, this.mSearchDirectory);
        } else {
            doSearch(voiceQuery, this.mQuery, this.mSearchDirectory);
        }

    }

    /**
     * Method that ask the user before do the search.
     *
     * @param voiceQuery Indicates if the query is from voice recognition
     * @param query The terms of the search
     * @param searchDirectory The directory of the search
     */
    private void askUserBeforeSearch(
            final boolean voiceQuery, final Query query, final String searchDirectory) {
        //Show a dialog asking the user
        AlertDialog dialog =
                DialogHelper.createYesNoDialog(
                        this, R.string.search_few_characters_msg,
                        new DialogInterface.OnClickListener() {
                            @Override
                            @SuppressWarnings("synthetic-access")
                            public void onClick(DialogInterface alertDialog, int which) {
                                if (which == DialogInterface.BUTTON_NEGATIVE) {
                                    doSearch(voiceQuery, query, searchDirectory);
                                    return;
                                }

                                //Close search activity
                                back(true, null);
                            }
                       });
        dialog.show();
    }

    /**
     * Method that do the search.
     *
     * @param voiceQuery Indicates if the query is from voice recognition
     * @param query The terms of the search
     * @param searchDirectory The directory of the search
     */
    private void doSearch(
            final boolean voiceQuery, final Query query, final String searchDirectory) {

        //Save every query for use as recent suggestions
        SearchRecentSuggestions suggestions =
                new SearchRecentSuggestions(this,
                        RecentSearchesContentProvider.AUTHORITY,
                        RecentSearchesContentProvider.MODE);
        if (!voiceQuery) {
            List<String> queries = query.getQueries();
            for (int i = 0; i < queries.size(); i++) {
                suggestions.saveRecentQuery(queries.get(i), null);
            }
        }

        //Set the listview
        this.mResultList = new ArrayList<FileSystemObject>();
        this.mAdapter =
                new SearchResultAdapter(this,
                        new ArrayList<SearchResult>(), R.layout.search_item, this.mQuery);
        this.mSearchListView.setAdapter(this.mAdapter);

        //Set terms
        this.mSearchTerms.setText(
                Html.fromHtml(getString(R.string.search_terms, query.getTerms())));


        //Now, do the search in background
        this.mSearchListView.post(new Runnable() {
            @Override
            @SuppressWarnings("synthetic-access")
            public void run() {
                try {
                    //Retrieve the terms of the search
                    String label = getString(R.string.searching_action_label);

                    //Show a dialog for the progress
                    SearchActivity.this.mDialog =
                            new MessageProgressDialog(
                                    SearchActivity.this,
                                    R.drawable.ic_holo_light_search, R.string.searching,
                                    label, R.plurals.search_found_items);
                    SearchActivity.this.mDialog.setOnCancelListener(
                            new MessageProgressDialog.OnCancelListener() {
                                @Override
                                public boolean onCancel() {
                                    //User has requested the cancellation of the search
                                    //Broadcast the cancellation
                                    if (!SearchActivity.this.mExecutable.isCanceled()) {
                                        if (SearchActivity.this.mExecutable.cancel()) {
                                            if (SearchActivity.this.mAdapter != null) {
                                                SearchActivity.this.toggleResults(
                                                   SearchActivity.this.mAdapter.getCount() > 0);
                                            }
                                            return true;
                                        }
                                        return false;
                                    }
                                    return true;
                                }
                            });
                    SearchActivity.this.mDialog.show();

                    //Execute the query (search are process in background)
                    SearchActivity.this.mExecutable =
                            CommandHelper.findFiles(
                                    SearchActivity.this,
                                    searchDirectory,
                                    SearchActivity.this.mQuery,
                                    SearchActivity.this,
                                    null);

                } catch (Throwable ex) {
                    //Remove all elements
                    try {
                        SearchActivity.this.removeAll();
                    } catch (Throwable ex2) {
                        /**NON BLOCK**/
                    }
                    try {
                        if (SearchActivity.this.mDialog != null) {
                            SearchActivity.this.mDialog.dismiss();
                        }
                    } catch (Throwable ex2) {
                        /**NON BLOCK**/
                    }

                    //Capture the exception
                    Log.e(TAG, "Search failed", ex); //$NON-NLS-1$
                    DialogHelper.showToast(
                            SearchActivity.this,
                            R.string.search_error_msg, Toast.LENGTH_SHORT);
                    SearchActivity.this.mSearchListView.setVisibility(View.GONE);
                }
            }
        });
    }

    /**
     * Method that restore the activity from the cached data.
     */
    private void loadFromCacheData() {
        this.mSearchListView.post(new Runnable() {
            @Override
            @SuppressWarnings("synthetic-access")
            public void run() {
                //Toggle results
                List<SearchResult> list = SearchActivity.this.mRestoreState.getSearchResultList();
                String directory = SearchActivity.this.mRestoreState.getSearchDirectory();
                SearchActivity.this.toggleResults(list.size() > 0);
                setFoundItems(list.size(), directory);

                //Set terms
                Query query = SearchActivity.this.mRestoreState.getSearchQuery();
                String terms =
                        TextUtils.join(" | ",  //$NON-NLS-1$;
                                query.getQueries().toArray(new String[]{}));
                if (terms.endsWith(" | ")) { //$NON-NLS-1$;
                    terms = ""; //$NON-NLS-1$;
                }
                SearchActivity.this.mSearchTerms.setText(
                        Html.fromHtml(getString(R.string.search_terms, terms)));

                try {
                    if (SearchActivity.this.mSearchWaiting != null) {
                        SearchActivity.this.mSearchWaiting.setVisibility(View.VISIBLE);
                    }

                    //Add list to the listview
                    if (SearchActivity.this.mSearchListView.getAdapter() != null) {
                        ((SearchResultAdapter)SearchActivity.this.
                                mSearchListView.getAdapter()).clear();
                    }
                    SearchActivity.this.mSearchListView.setAdapter(
                            new SearchResultAdapter(
                                    SearchActivity.this.mSearchListView.getContext(),
                                    list,
                                    R.layout.search_item,
                                    query));
                    SearchActivity.this.mSearchListView.setSelection(0);

                } catch (Throwable ex) {
                    //Capture the exception
                    ExceptionUtil.translateException(SearchActivity.this, ex);

                } finally {
                    //Hide waiting
                    if (SearchActivity.this.mSearchWaiting != null) {
                        SearchActivity.this.mSearchWaiting.setVisibility(View.GONE);
                    }
                }
            }
        });
    }


    /**
     * Method that filter the user queries for valid queries only.<br/>
     * <br/>
     * Only allow query strings with more that 3 characters
     *
     * @param original The original user queries
     * @return List<String> The list of queries filtered
     */
    @SuppressWarnings("static-method")
    private List<String> filterQuery(List<String> original) {
        List<String> dst = new ArrayList<String>(original);
        for (int i = dst.size() - 1; i >= 0; i--) {
            String query = dst.get(i);
            if (query == null || query.trim().length() < MIN_CHARS_SEARCH) {
                dst.remove(i);
            }
        }
        return dst;
    }

    /**
     * Method that removes all items and display a message.
     */
    private void removeAll() {
        this.mAdapter.clear();
        this.mAdapter.notifyDataSetChanged();
        this.mSearchListView.setSelection(0);
        toggleResults(false);
    }

    /**
     * Method that toggle the views when there are results.
     *
     * @param hasResults Indicates if there are results
     */
    private void toggleResults(boolean hasResults) {
        this.mSearchListView.setVisibility(hasResults ? View.VISIBLE : View.INVISIBLE);
        this.mEmptyListMsg.setVisibility(!hasResults ? View.VISIBLE : View.INVISIBLE);
    }

    /**
     * Method that display the number of found items.
     *
     * @param items The number of items
     * @param searchDirectory The search directory path
     */
    private void setFoundItems(final int items, final String searchDirectory) {
        if (this.mSearchFoundItems != null) {
            this.mSearchFoundItems.post(new Runnable() {
                @Override
                @SuppressWarnings("synthetic-access")
                public void run() {
                    String foundItems =
                            getResources().
                                getQuantityString(
                                    R.plurals.search_found_items, items, new Integer(items));
                    SearchActivity.this.mSearchFoundItems.setText(
                                            getString(
                                                R.string.search_found_items_in_directory,
                                                foundItems,
                                                searchDirectory));
                }
            });
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                back(true, null);
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
       switch (item.getItemId()) {
          case android.R.id.home:
              back(true, null);
              return true;
          default:
             return super.onOptionsItemSelected(item);
       }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        try {
            SearchResult result = ((SearchResultAdapter)parent.getAdapter()).getItem(position);
            FileSystemObject fso = result.getFso();
            if (fso instanceof ParentDirectory) {
                back(false, fso.getParent());
            } else if (fso instanceof Directory) {
                back(false, fso.getFullPath());
            } else if (fso instanceof Symlink) {
                Symlink symlink = (Symlink)fso;
                if (symlink.getLinkRef() != null && symlink.getLinkRef() instanceof Directory) {
                    back(false, symlink.getLinkRef().getFullPath());
                }
            }
        } catch (Throwable ex) {
            ExceptionUtil.translateException(this.mSearchListView.getContext(), ex);
        }

    }

    /**
     * Method that returns to previous activity.
     *
     * @param canceled Indicates if the activity was canceled
     * @param directory The directory to which navigate to
     */
    private void back(final boolean canceled, String directory) {
        Intent intent =  new Intent();
        if (canceled) {
            if (SearchActivity.this.mDrawingSearchResultTask != null
                    && SearchActivity.this.mDrawingSearchResultTask.isRunning()) {
                SearchActivity.this.mDrawingSearchResultTask.cancel(true);
            }
            if (this.mRestoreState != null) {
                intent.putExtra(
                        NavigationActivity.EXTRA_SEARCH_LAST_SEARCH_DATA,
                        (Parcelable)this.mRestoreState);
            }
            setResult(RESULT_CANCELED, intent);
        } else {
            intent.putExtra(NavigationActivity.EXTRA_SEARCH_ENTRY_SELECTION, directory);
            intent.putExtra(
                    NavigationActivity.EXTRA_SEARCH_LAST_SEARCH_DATA,
                    (Parcelable)createSearchInfo());
            setResult(RESULT_OK, intent);
        }
        finish();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onAsyncStart() {
        runOnUiThread(new Runnable() {
            @Override
            @SuppressWarnings("synthetic-access")
            public void run() {
                SearchActivity.this.toggleResults(false);
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onAsyncEnd(boolean cancelled) {
        this.mSearchListView.post(new Runnable() {
            @Override
            @SuppressWarnings("synthetic-access")
            public void run() {
                try {
                    //Dismiss the dialog
                    if (SearchActivity.this.mDialog != null) {
                        SearchActivity.this.mDialog.dismiss();
                    }

                    //Toggle results
                    SearchActivity.this.toggleResults(
                            SearchActivity.this.mResultList.size() > 0);
                    setFoundItems(
                            SearchActivity.this.mResultList.size(),
                            SearchActivity.this.mSearchDirectory);

                    //Create the task for drawing the data
                    SearchActivity.this.mDrawingSearchResultTask =
                                            new SearchResultDrawingAsyncTask(
                                                    SearchActivity.this.mSearchListView,
                                                    SearchActivity.this.mSearchWaiting,
                                                    SearchActivity.this.mResultList,
                                                    SearchActivity.this.mQuery);
                    SearchActivity.this.mDrawingSearchResultTask.execute();

                } catch (Throwable ex) {
                    Log.e(TAG, "onAsyncEnd method fails", ex); //$NON-NLS-1$
                }
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onPartialResult(final List<FileSystemObject> partialResults) {
      //Saved in the global result list, for save at the end
        SearchActivity.this.mResultList.addAll(partialResults);

        //Notify progress
        this.mSearchListView.post(new Runnable() {
            @Override
            @SuppressWarnings("synthetic-access")
            public void run() {
                if (SearchActivity.this.mDialog != null) {
                    SearchActivity.this.mDialog.setProgress(
                            SearchActivity.this.mResultList.size());
                }
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onException(Exception cause) {
        //Capture the exception
        ExceptionUtil.translateException(this, cause);
    }

    /**
     * Method that creates a {@link SearchInfoParcelable} reference from
     * the current data.
     *
     * @return SearchInfoParcelable The search info reference
     */
    private SearchInfoParcelable createSearchInfo() {
        SearchInfoParcelable parcel = new SearchInfoParcelable();
        parcel.setSearchDirectory(this.mSearchDirectory);
        parcel.setSearchResultList(
                ((SearchResultAdapter)this.mSearchListView.getAdapter()).getData());
        parcel.setSearchQuery(this.mQuery);
        return parcel;
    }
}
