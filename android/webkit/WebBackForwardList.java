/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.webkit;

import android.util.Config;
import java.io.Serializable;
import java.util.ArrayList;

/**
 * This class contains the back/forward list for a WebView.
 * WebView.copyBackForwardList() will return a copy of this class used to
 * inspect the entries in the list.
 */
public class WebBackForwardList implements Cloneable, Serializable {
    // Current position in the list.
    private int mCurrentIndex;
    // ArrayList of WebHistoryItems for maintaining our copy.
    private ArrayList<WebHistoryItem> mArray;
    // Flag to indicate that the list is invalid
    private boolean mClearPending;

    /**
     * Construct a back/forward list used by clients of WebView.
     */
    /*package*/ WebBackForwardList() {
        mCurrentIndex = -1;
        mArray = new ArrayList<WebHistoryItem>();
    }

    /**
     * Return the current history item. This method returns null if the list is
     * empty.
     * @return The current history item.
     */
    public synchronized WebHistoryItem getCurrentItem() {
        return getItemAtIndex(mCurrentIndex);
    }

    /**
     * Get the index of the current history item. This index can be used to
     * directly index into the array list.
     * @return The current index from 0...n or -1 if the list is empty.
     */
    public synchronized int getCurrentIndex() {
        return mCurrentIndex;
    }

    /**
     * Get the history item at the given index. The index range is from 0...n
     * where 0 is the first item and n is the last item.
     * @param index The index to retrieve.
     */
    public synchronized WebHistoryItem getItemAtIndex(int index) {
        if (index < 0 || index >= getSize()) {
            return null;
        }
        return mArray.get(index);
    }

    /**
     * Get the total size of the back/forward list.
     * @return The size of the list.
     */
    public synchronized int getSize() {
        return mArray.size();
    }

    /**
     * Mark the back/forward list as having a pending clear. This is used on the
     * UI side to mark the list as being invalid during the clearHistory method.
     */
    /*package*/ synchronized void setClearPending() {
        mClearPending = true;
    }

    /**
     * Return the status of the clear flag. This is used on the UI side to
     * determine if the list is valid for checking things like canGoBack.
     */
    /*package*/ synchronized boolean getClearPending() {
        return mClearPending;
    }

    /**
     * Add a new history item to the list. This will remove all items after the
     * current item and append the new item to the end of the list. Called from
     * the WebCore thread only. Synchronized because the UI thread may be
     * reading the array or the current index.
     * @param item A new history item.
     */
    /*package*/ synchronized void addHistoryItem(WebHistoryItem item) {
        // Update the current position because we are going to add the new item
        // in that slot.
        ++mCurrentIndex;
        // If the current position is not at the end, remove all history items
        // after the current item.
        final int size = mArray.size();
        final int newPos = mCurrentIndex;
        if (newPos != size) {
            for (int i = size - 1; i >= newPos; i--) {
                final WebHistoryItem h = mArray.remove(i);
            }
        }
        // Add the item to the list.
        mArray.add(item);
    }

    /**
     * Clear the back/forward list. Called from the WebCore thread.
     */
    /*package*/ synchronized void close(int nativeFrame) {
        // Clear the array first because nativeClose will call addHistoryItem
        // with the current item.
        mArray.clear();
        mCurrentIndex = -1;
        nativeClose(nativeFrame);
        // Reset the clear flag
        mClearPending = false;
    }

    /* Remove the item at the given index. Called by JNI only. */
    private synchronized void removeHistoryItem(int index) {
        // XXX: This is a special case. Since the callback is only triggered
        // when removing the first item, we can assert that the index is 0.
        // This lets us change the current index without having to query the
        // native BackForwardList.
        if (Config.DEBUG && (index != 0)) {
            throw new AssertionError();
        }
        final WebHistoryItem h = mArray.remove(index);
        // XXX: If we ever add another callback for removing history items at
        // any index, this will no longer be valid.
        mCurrentIndex--;
    }

    /**
     * Clone the entire object to be used in the UI thread by clients of
     * WebView. This creates a copy that should never be modified by any of the
     * webkit package classes.
     */
    protected synchronized WebBackForwardList clone() {
        WebBackForwardList l = new WebBackForwardList();
        if (mClearPending) {
            // If a clear is pending, return a copy with only the current item.
            l.addHistoryItem(getCurrentItem());
            return l;
        }
        l.mCurrentIndex = mCurrentIndex;
        int size = getSize();
        l.mArray = new ArrayList<WebHistoryItem>(size);
        for (int i = 0; i < size; i++) {
            // Add a copy of each WebHistoryItem
            l.mArray.add(mArray.get(i).clone());
        }
        return l;
    }

    /**
     * Set the new history index.
     * @param newIndex The new history index.
     */
    /*package*/ synchronized void setCurrentIndex(int newIndex) {
        mCurrentIndex = newIndex;
    }

    /**
     * Restore the history index.
     */
    /*package*/ static native synchronized void restoreIndex(int nativeFrame,
            int index);

    /* Close the native list. */
    private static native void nativeClose(int nativeFrame);
}
