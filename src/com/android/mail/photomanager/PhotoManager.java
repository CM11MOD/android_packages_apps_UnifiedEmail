/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.mail.photomanager;

import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import android.content.ComponentCallbacks2;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.HandlerThread;
import android.os.Message;
import android.util.LruCache;

import com.android.mail.ui.ImageCanvas;
import com.android.mail.utils.LogUtils;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronously loads photos and maintains a cache of photos
 */
public abstract class PhotoManager implements ComponentCallbacks2, Callback {
    /**
     * Get the default image provider that draws while the photo is being
     * loaded.
     */
    public abstract DefaultImageProvider getDefaultImageProvider();

    /**
     * Generate a hashcode unique to each request.
     */
    public abstract long getHash(PhotoIdentifier id, ImageCanvas view);

    /**
     * Return a specific implementation of PhotoLoaderThread.
     */
    public abstract PhotoLoaderThread getLoaderThread(ContentResolver contentResolver);

    /**
     * The bitmap cache is only big enough to hold a few items. The bigger each
     * photo will be, the smaller the capacity should be.
     *
     * @see #BITMAP_CACHE_SIZE
     */
    public abstract int getCapacityOfBitmapCache();

    static final String TAG = "PhotoManager";
    static final boolean DEBUG = false; // Don't submit with true
    static final boolean DEBUG_SIZES = false; // Don't submit with true

    private static final String LOADER_THREAD_NAME = "PhotoLoader";

    /**
     * Type of message sent by the UI thread to itself to indicate that some photos
     * need to be loaded.
     */
    private static final int MESSAGE_REQUEST_LOADING = 1;

    /**
     * Type of message sent by the loader thread to indicate that some photos have
     * been loaded.
     */
    private static final int MESSAGE_PHOTOS_LOADED = 2;

    public interface DefaultImageProvider {
        /**
         * Applies the default avatar to the DividedImageView. Extent is an
         * indicator for the size (width or height). If darkTheme is set, the
         * avatar is one that looks better on dark background
         * @param id
         */
        public void applyDefaultImage(PhotoIdentifier id, ImageCanvas view, int extent);
    }

    /**
     * Maintains the state of a particular photo.
     */
    private static class BitmapHolder {
        byte[] bytes;

        volatile boolean fresh;

        public BitmapHolder(byte[] bytes, int originalSmallerExtent) {
            this.bytes = bytes;
            this.fresh = true;
        }
    }

    /**
     * An LRU cache for bitmap holders. The cache contains bytes for photos just
     * as they come from the database. Each holder has a soft reference to the
     * actual bitmap. The keys are decided by the implementation.
     */
    private static final LruCache<Object, BitmapHolder> sBitmapHolderCache;

    /**
     * Level 2 LRU cache for bitmaps. This is a smaller cache that holds
     * the most recently used bitmaps to save time on decoding
     * them from bytes (the bytes are stored in {@link #sBitmapHolderCache}.
     * The keys are decided by the implementation.
     */
    private static final LruCache<Object, Bitmap> sBitmapCache;

    /** Cache size for {@link #sBitmapHolderCache} for devices with "large" RAM. */
    private static final int HOLDER_CACHE_SIZE = 2000000;

    /** Cache size for {@link #sBitmapCache} for devices with "large" RAM. */
    private static final int BITMAP_CACHE_SIZE = 36864 * 48; // 1728K

    /**
     * {@code true} if ALL entries in {@link #sBitmapHolderCache} are NOT fresh.
     */
    private static volatile boolean sBitmapHolderCacheAllUnfresh = true;

    /**
     * Cache size threshold at which bitmaps will not be preloaded.
     */
    private static final int sBitmapHolderCacheRedZoneBytes;

    /** For debug: How many times we had to reload cached photo for a stale entry */
    private static final AtomicInteger sStaleCacheOverwrite = new AtomicInteger();

    /** For debug: How many times we had to reload cached photo for a fresh entry.  Should be 0. */
    private static final AtomicInteger sFreshCacheOverwrite = new AtomicInteger();

    static {
        final float cacheSizeAdjustment =
                (MemoryUtils.getTotalMemorySize() >= MemoryUtils.LARGE_RAM_THRESHOLD) ?
                        1.0f : 0.5f;
        final int bitmapCacheSize = (int) (cacheSizeAdjustment * BITMAP_CACHE_SIZE);
        sBitmapCache = new LruCache<Object, Bitmap>(bitmapCacheSize) {
            @Override protected int sizeOf(Object key, Bitmap value) {
                return value.getByteCount();
            }

            @Override protected void entryRemoved(
                    boolean evicted, Object key, Bitmap oldValue, Bitmap newValue) {
                if (DEBUG) dumpStats();
            }
        };
        final int holderCacheSize = (int) (cacheSizeAdjustment * HOLDER_CACHE_SIZE);
        sBitmapHolderCache = new LruCache<Object, BitmapHolder>(holderCacheSize) {
            @Override protected int sizeOf(Object key, BitmapHolder value) {
                return value.bytes != null ? value.bytes.length : 0;
            }

            @Override protected void entryRemoved(
                    boolean evicted, Object key, BitmapHolder oldValue, BitmapHolder newValue) {
                if (DEBUG) dumpStats();
            }
        };
        sBitmapHolderCacheRedZoneBytes = (int) (holderCacheSize * 0.75);
        LogUtils.i(TAG, "Cache adj: " + cacheSizeAdjustment);
        if (DEBUG) {
            LogUtils.d(TAG, "Cache size: " + btk(sBitmapHolderCache.maxSize())
                    + " + " + btk(sBitmapCache.maxSize()));
        }
    }

    /**
     * A map from ImageCanvas hashcode to the corresponding photo ID or uri,
     * encapsulated in a request. The request may swapped out before the photo
     * loading request is started.
     */
    private final ConcurrentHashMap<Long, Request> mPendingRequests =
            new ConcurrentHashMap<Long, Request>();

    /**
     * Handler for messages sent to the UI thread.
     */
    private final Handler mMainThreadHandler = new Handler(this);

    /**
     * Thread responsible for loading photos from the database. Created upon
     * the first request.
     */
    private PhotoLoaderThread mLoaderThread;

    /**
     * A gate to make sure we only send one instance of MESSAGE_PHOTOS_NEEDED at a time.
     */
    private boolean mLoadingRequested;

    /**
     * Flag indicating if the image loading is paused.
     */
    private boolean mPaused;

    private final Context mContext;

    public PhotoManager(Context context) {
        mContext = context;
    }

    public void loadThumbnail(PhotoIdentifier id, ImageCanvas view) {
        long hashCode = getHash(id, view);
        DefaultImageProvider defaultProvider = getDefaultImageProvider();
        if (!id.isValid()) {
            // No photo is needed
            defaultProvider.applyDefaultImage(id, view, -1);
            mPendingRequests.remove(hashCode);
        } else {
            if (DEBUG)
                LogUtils.d(TAG, "loadPhoto request: " + id.getKey());
            loadPhoto(hashCode, Request.create(id, defaultProvider, view));
        }
    }

    private void loadPhoto(Long hashCode, Request request) {
        boolean loaded = loadCachedPhoto(request, false);
        if (loaded) {
            mPendingRequests.remove(hashCode);
        } else {
            mPendingRequests.put(hashCode, request);
            if (!mPaused) {
                // Send a request to start loading photos
                requestLoading();
            }
        }
    }

    /**
     * Remove photo from the supplied image view. This also cancels current pending load request
     * inside this photo manager.
     */
    public void removePhoto(Long hash) {
        Request r = mPendingRequests.get(hash);
        if (r != null) {
            r.getView().reset();
            mPendingRequests.remove(hash);
        }
    }

    /**
     * Marks all cached photos for reloading.  We can continue using cache but should
     * also make sure the photos haven't changed in the background and notify the views
     * if so.
     */
    public void refreshCache() {
        if (sBitmapHolderCacheAllUnfresh) {
            if (DEBUG) LogUtils.d(TAG, "refreshCache -- no fresh entries.");
            return;
        }
        if (DEBUG) LogUtils.d(TAG, "refreshCache");
        sBitmapHolderCacheAllUnfresh = true;
        for (BitmapHolder holder : sBitmapHolderCache.snapshot().values()) {
            holder.fresh = false;
        }
    }

    /**
     * Initiates a background process that over time will fill up cache with
     * preload photos.
     */
    public void preloadPhotosInBackground() {
        ensureLoaderThread();
        mLoaderThread.requestPreloading();
    }

    public void ensureLoaderThread() {
        if (mLoaderThread == null) {
            mLoaderThread = getLoaderThread(mContext.getContentResolver());
            mLoaderThread.start();
        }
    }

    /**
     * Checks if the photo is present in cache.  If so, sets the photo on the view.
     *
     * @return false if the photo needs to be (re)loaded from the provider.
     */
    private boolean loadCachedPhoto(Request request, boolean fadeIn) {
        BitmapHolder holder = sBitmapHolderCache.get(request.getKey());
        if (holder == null) {
            // The bitmap has not been loaded ==> show default avatar
            request.applyDefaultImage();
            return false;
        }

        if (holder.bytes == null) {
            request.applyDefaultImage();
            return holder.fresh;
        }

        Bitmap cachedBitmap = request.getView()
                .loadImage(holder.bytes, request.getKey().toString());

        // Put the bitmap in the LRU cache. But only do this for images that are small enough
        // (getCapacityofBitmapCache() gives us the number of bitmaps we want to fit)
        if (cachedBitmap != null && cachedBitmap.getByteCount()
                < sBitmapCache.maxSize() / getCapacityOfBitmapCache()) {
            sBitmapCache.put(request.getKey(), cachedBitmap);
        }

        return holder.fresh;
    }

    /**
     * Temporarily stops loading photos from the database.
     */
    public void pause() {
        mPaused = true;
    }

    /**
     * Resumes loading photos from the database.
     */
    public void resume() {
        mPaused = false;
        if (DEBUG) dumpStats();
        if (!mPendingRequests.isEmpty()) {
            requestLoading();
        }
    }

    /**
     * Sends a message to this thread itself to start loading images.  If the current
     * view contains multiple image views, all of those image views will get a chance
     * to request their respective photos before any of those requests are executed.
     * This allows us to load images in bulk.
     */
    private void requestLoading() {
        if (!mLoadingRequested) {
            mLoadingRequested = true;
            mMainThreadHandler.sendEmptyMessage(MESSAGE_REQUEST_LOADING);
        }
    }

    /**
     * Processes requests on the main thread.
     */
    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MESSAGE_REQUEST_LOADING: {
                mLoadingRequested = false;
                if (!mPaused) {
                    ensureLoaderThread();
                    mLoaderThread.requestLoading();
                }
                return true;
            }

            case MESSAGE_PHOTOS_LOADED: {
                if (!mPaused) {
                    processLoadedImages();
                }
                if (DEBUG) dumpStats();
                return true;
            }
        }
        return false;
    }

    /**
     * Goes over pending loading requests and displays loaded photos.  If some of the
     * photos still haven't been loaded, sends another request for image loading.
     */
    private void processLoadedImages() {
        Iterator<Long> iterator = mPendingRequests.keySet().iterator();
        while (iterator.hasNext()) {
            Long hash = iterator.next();
            Request request = mPendingRequests.get(hash);
            boolean loaded = loadCachedPhoto(request, true);
            if (loaded) {
                iterator.remove();
            }
        }

        // TODO: this already seems to happen when calling loadCachedPhoto
        //softenCache();

        if (!mPendingRequests.isEmpty()) {
            requestLoading();
        }
    }

    /**
     * Stores the supplied bitmap in cache.
     */
    private static void cacheBitmap(final Object key, final byte[] bytes) {
        if (DEBUG) {
            BitmapHolder prev = sBitmapHolderCache.get(key);
            if (prev != null && prev.bytes != null) {
                LogUtils.d(TAG, "Overwriting cache: key=" + key
                        + (prev.fresh ? " FRESH" : " stale"));
                if (prev.fresh) {
                    sFreshCacheOverwrite.incrementAndGet();
                } else {
                    sStaleCacheOverwrite.incrementAndGet();
                }
            }
            LogUtils.d(TAG, "Caching data: key=" + key + ", "
                    + (bytes == null ? "<null>" : btk(bytes.length)));
        }
        BitmapHolder holder = new BitmapHolder(bytes, bytes == null ? -1
                : BitmapUtil.getSmallerExtentFromBytes(bytes));

        sBitmapHolderCache.put(key, holder);
        sBitmapHolderCacheAllUnfresh = false;
    }

    // ComponentCallbacks2
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
    }

    // ComponentCallbacks2
    @Override
    public void onLowMemory() {
    }

    // ComponentCallbacks2
    @Override
    public void onTrimMemory(int level) {
        if (DEBUG) LogUtils.d(TAG, "onTrimMemory: " + level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            // Clear the caches.  Note all pending requests will be removed too.
            clear();
        }
    }

    public void clear() {
        if (DEBUG) LogUtils.d(TAG, "clear");
        mPendingRequests.clear();
        sBitmapHolderCache.evictAll();
        sBitmapCache.evictAll();
    }

    /**
     * Dump cache stats on logcat.
     */
    private static void dumpStats() {
        if (!DEBUG) {
            return;
        }
        int numHolders = 0;
        int rawBytes = 0;
        int bitmapBytes = 0;
        int numBitmaps = 0;
        for (BitmapHolder h : sBitmapHolderCache.snapshot().values()) {
            numHolders++;
            if (h.bytes != null) {
                rawBytes += h.bytes.length;
            }
        }
        LogUtils.d(TAG,
                "L1: " + btk(rawBytes) + " + " + btk(bitmapBytes) + " = "
                        + btk(rawBytes + bitmapBytes) + ", " + numHolders + " holders, "
                        + numBitmaps + " bitmaps, avg: " + btk(safeDiv(rawBytes, numHolders)) + ","
                        + btk(safeDiv(bitmapBytes, numBitmaps)));
        LogUtils.d(TAG, "L1 Stats: " + sBitmapHolderCache.toString() + ", overwrite: fresh="
                + sFreshCacheOverwrite.get() + " stale=" + sStaleCacheOverwrite.get());

        numBitmaps = 0;
        bitmapBytes = 0;
        for (Bitmap b : sBitmapCache.snapshot().values()) {
            numBitmaps++;
            bitmapBytes += b.getByteCount();
        }
        LogUtils.d(TAG, "L2: " + btk(bitmapBytes) + ", " + numBitmaps + " bitmaps" + ", avg: "
                + btk(safeDiv(bitmapBytes, numBitmaps)));
        // We don't get from L2 cache, so L2 stats is meaningless.
    }

    /** Converts bytes to K bytes, rounding up.  Used only for debug log. */
    private static String btk(int bytes) {
        return ((bytes + 1023) / 1024) + "K";
    }

    private static final int safeDiv(int dividend, int divisor) {
        return (divisor  == 0) ? 0 : (dividend / divisor);
    }

    public interface PhotoIdentifier {
        /**
         * If this returns false, the PhotoManager will not attempt to load the
         * bitmap. Instead, the default image provider will be used.
         */
        public boolean isValid();
        public Object getKey();
    }

    /**
     * The thread that performs loading of photos from the database.
     */
    /**
     * TODO: Insert description here. (generated by markwei)
     */
    protected abstract class PhotoLoaderThread extends HandlerThread implements Callback {

        /**
         * A pause between preload batches that yields to the UI thread.
         */
        protected abstract int getPhotoPreloadDelay();

        /**
         * Number of photos to preload per batch.
         */
        protected abstract int getPreloadBatch();

        /**
         * Called before we request preloading. Fill up the provided List with
         * ids to be preloaded.
         */
        protected abstract void queryAndAddPhotosForPreload(List<Object> preloadPhotoIds);

        /**
         * Do a query on one or more ContentProviders and return the resulting
         * photos. Remove from photoIds the processed ids.
         */
        protected abstract Map<Object, byte[]> preloadPhotos(Set<Object> photoIds);

        /**
         * Return photos mapped from {@link Request#getKey()} to the photo for
         * that request.
         */
        protected abstract Map<String, byte[]> loadPhotos(Collection<Request> requests);

        private static final int MESSAGE_PRELOAD_PHOTOS = 0;
        private static final int MESSAGE_LOAD_PHOTOS = 1;

        private final ContentResolver mResolver;

        private Handler mLoaderThreadHandler;

        private static final int PRELOAD_STATUS_NOT_STARTED = 0;
        private static final int PRELOAD_STATUS_IN_PROGRESS = 1;
        private static final int PRELOAD_STATUS_DONE = 2;

        private int mPreloadStatus = PRELOAD_STATUS_NOT_STARTED;

        /**
         * List of all photo ids to be preloaded. Implementation decides what
         * these ids are.
         */
        private final List<Object> mPreloadPhotos = Lists.newArrayList();
        /**
         * Set of photo ids in the current batch to be preloaded.
         */
        private final Set<Object> mCurrentPreloadPhotos = Sets.newHashSet();

        public PhotoLoaderThread(ContentResolver resolver) {
            super(LOADER_THREAD_NAME);
            mResolver = resolver;
        }

        protected ContentResolver getResolver() {
            return mResolver;
        }

        public void ensureHandler() {
            if (mLoaderThreadHandler == null) {
                mLoaderThreadHandler = new Handler(getLooper(), this);
            }
        }

        /**
         * Kicks off preloading of the next batch of photos on the background thread.
         * Preloading will happen after a delay: we want to yield to the UI thread
         * as much as possible.
         * <p>
         * If preloading is already complete, does nothing.
         */
        public void requestPreloading() {
            if (mPreloadStatus == PRELOAD_STATUS_DONE) {
                return;
            }

            ensureHandler();
            if (mLoaderThreadHandler.hasMessages(MESSAGE_LOAD_PHOTOS)) {
                return;
            }

            mLoaderThreadHandler.sendEmptyMessageDelayed(
                    MESSAGE_PRELOAD_PHOTOS, getPhotoPreloadDelay());
        }

        /**
         * Sends a message to this thread to load requested photos.  Cancels a preloading
         * request, if any: we don't want preloading to impede loading of the photos
         * we need to display now.
         */
        public void requestLoading() {
            ensureHandler();
            mLoaderThreadHandler.removeMessages(MESSAGE_PRELOAD_PHOTOS);
            mLoaderThreadHandler.sendEmptyMessage(MESSAGE_LOAD_PHOTOS);
        }

        /**
         * Receives the above message, loads photos and then sends a message
         * to the main thread to process them.
         */
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_PRELOAD_PHOTOS:
                    preloadPhotosInBackground();
                    break;
                case MESSAGE_LOAD_PHOTOS:
                    loadPhotosInBackground();
                    break;
            }
            return true;
        }

        /**
         * The first time it is called, figures out which photos need to be preloaded.
         * Each subsequent call preloads the next batch of photos and requests
         * another cycle of preloading after a delay.  The whole process ends when
         * we either run out of photos to preload or fill up cache.
         */
        private void preloadPhotosInBackground() {
            if (mPreloadStatus == PRELOAD_STATUS_DONE) {
                return;
            }

            if (mPreloadStatus == PRELOAD_STATUS_NOT_STARTED) {
                queryAndAddPhotosForPreload(mPreloadPhotos);
                if (mPreloadPhotos.isEmpty()) {
                    mPreloadStatus = PRELOAD_STATUS_DONE;
                } else {
                    mPreloadStatus = PRELOAD_STATUS_IN_PROGRESS;
                }
                requestPreloading();
                return;
            }

            if (sBitmapHolderCache.size() > sBitmapHolderCacheRedZoneBytes) {
                mPreloadStatus = PRELOAD_STATUS_DONE;
                return;
            }

            mCurrentPreloadPhotos.clear();

            int count = 0;
            int preloadSize = mPreloadPhotos.size();
            while(preloadSize > 0 && mCurrentPreloadPhotos.size() < getPreloadBatch()) {
                preloadSize--;
                count++;
                Object photoId = mPreloadPhotos.get(preloadSize);
                mCurrentPreloadPhotos.add(photoId);
                mPreloadPhotos.remove(preloadSize);
            }

            preloadThumbnails();

            if (preloadSize == 0) {
                mPreloadStatus = PRELOAD_STATUS_DONE;
            }

            LogUtils.v(TAG, "Preloaded " + count + " photos.  Cached bytes: "
                    + sBitmapHolderCache.size());

            requestPreloading();
        }

        private void loadPhotosInBackground() {
            Collection<Request> requests = new HashSet<PhotoManager.Request>();

            /*
             * Since the call is made from the loader thread, the map could be
             * changing during the iteration. That's not really a problem:
             * ConcurrentHashMap will allow those changes to happen without throwing
             * exceptions. Since we may miss some requests in the situation of
             * concurrent change, we will need to check the map again once loading
             * is complete.
             */
            Iterator<Request> iterator = mPendingRequests.values().iterator();
            while (iterator.hasNext()) {
                Request request = iterator.next();
                final BitmapHolder holder = sBitmapHolderCache.get(request.getKey());
                if (holder == null || holder.bytes == null || !holder.fresh) {
                    requests.add(request);
                }
            }
            Map<String, byte[]> photosMap = loadPhotos(requests);
            for (String key : photosMap.keySet()) {
                cacheBitmap(key, photosMap.get(key));
            }
            mMainThreadHandler.sendEmptyMessage(MESSAGE_PHOTOS_LOADED);
            requestPreloading();
        }

        /** Loads thumbnail photos with ids */
        private void preloadThumbnails() {
            Map<Object, byte[]> photos = null;
            photos = preloadPhotos(mCurrentPreloadPhotos);

            if (photos != null) {
                for (Object id : photos.keySet()) {
                    byte[] bytes = photos.get(id);
                    cacheBitmap(id, bytes);
                }
            }
            mMainThreadHandler.sendEmptyMessage(MESSAGE_PHOTOS_LOADED);
        }

        protected String createInQuery(String value, int itemCount) {
            // Build first query
            StringBuilder query = new StringBuilder().append(value + " IN (");
            appendQuestionMarks(query, itemCount);
            query.append(')');
            return query.toString();
        }

        protected void appendQuestionMarks(StringBuilder query, int itemCount) {
            boolean first = true;
            for (int i = 0; i < itemCount; i++) {
                if (first) {
                    first = false;
                } else {
                    query.append(',');
                }
                query.append('?');
            }
        }

        protected void onLoaded(Set<Object> loadedIds) {
            if (mPreloadStatus == PRELOAD_STATUS_IN_PROGRESS) {
                for (Object id : loadedIds) {
                    mPreloadPhotos.remove(id);
                }
                if (mPreloadPhotos.isEmpty()) {
                    mPreloadStatus = PRELOAD_STATUS_DONE;
                }
            }
        }
    }

    /**
     * A holder for a contact photo request.
     */
    public static final class Request {
        private final int mRequestedExtent;
        private final DefaultImageProvider mDefaultProvider;
        private final PhotoIdentifier mPhotoIdentifier;
        private final ImageCanvas mView;

        private Request(PhotoIdentifier photoIdentifier, int requestedExtent,
                DefaultImageProvider defaultProvider, ImageCanvas view) {
            mPhotoIdentifier = photoIdentifier;
            mRequestedExtent = requestedExtent;
            mDefaultProvider = defaultProvider;
            mView = view;
        }

        public ImageCanvas getView() {
            return mView;
        }

        public static Request create(
                PhotoIdentifier id, DefaultImageProvider defaultProvider, ImageCanvas view) {
            return new Request(id, -1, defaultProvider, view);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(mRequestedExtent, mPhotoIdentifier, mView);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;
            final Request that = (Request) obj;
            if (mRequestedExtent != that.mRequestedExtent) return false;
            if (!Objects.equal(mPhotoIdentifier, that.mPhotoIdentifier)) return false;
            if (!Objects.equal(mView, that.mView)) return false;
            // Don't compare equality of mDarkTheme because it is only used in the default contact
            // photo case. When the contact does have a photo, the contact photo is the same
            // regardless of mDarkTheme, so we shouldn't need to put the photo request on the queue
            // twice.
            return true;
        }

        public Object getKey() {
            return mPhotoIdentifier.getKey();
        }

        public void applyDefaultImage() {
            mDefaultProvider.applyDefaultImage(mPhotoIdentifier, mView, mRequestedExtent);
        }
    }
}
