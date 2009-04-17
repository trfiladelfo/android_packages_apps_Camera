/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.camera;

import com.android.camera.gallery.IImage;

import android.graphics.Bitmap;
import android.net.Uri;

import java.util.ArrayList;

/**
 * A dedicated decoding thread used by ImageGallery.
 */
public class ImageLoader {
    @SuppressWarnings("unused")
    private static final String TAG = "ImageLoader";

    // queue of work to do in the worker thread
    private final ArrayList<WorkItem>      mQueue = new ArrayList<WorkItem>();
    private final ArrayList<WorkItem>      mInProgress = new ArrayList<WorkItem>();

    // the worker thread and a done flag so we know when to exit
    // currently we only exit from finalize
    private boolean                  mDone;
    private final ArrayList<Thread>        mDecodeThreads = new ArrayList<Thread>();
    private final android.os.Handler       mHandler;

    private int                      mThreadCount = 1;

    synchronized void clear(Uri uri) {
    }

    public interface LoadedCallback {
        public void run(Bitmap result);
    }

    public void pushToFront(final IImage image) {
        synchronized (mQueue) {
            WorkItem w = new WorkItem(image, 0, null, false);

            int existing = mQueue.indexOf(w);
            if (existing >= 1) {
                WorkItem existingWorkItem = mQueue.remove(existing);
                mQueue.add(0, existingWorkItem);
                mQueue.notifyAll();
            }
        }
    }

    public boolean cancel(final IImage image) {
        synchronized (mQueue) {
            WorkItem w = new WorkItem(image, 0, null, false);

            int existing = mQueue.indexOf(w);
            if (existing >= 0) {
                mQueue.remove(existing);
                return true;
            }
            return false;
        }
    }

    public Bitmap getBitmap(IImage image,
                            LoadedCallback imageLoadedRunnable,
                            boolean postAtFront,
                            boolean postBack) {
        return getBitmap(image, 0, imageLoadedRunnable, postAtFront, postBack);
    }

    public Bitmap getBitmap(IImage image,
                            int tag,
                            LoadedCallback imageLoadedRunnable,
                            boolean postAtFront,
                            boolean postBack) {
        synchronized (mDecodeThreads) {
            if (mDecodeThreads.size() == 0) {
                start();
            }
        }
        synchronized (mQueue) {
            WorkItem w =
                    new WorkItem(image, tag, imageLoadedRunnable, postBack);

            if (!mInProgress.contains(w)) {
                boolean contains = mQueue.contains(w);
                if (contains) {
                    if (postAtFront) {
                        // move this item to the front
                        mQueue.remove(w);
                        mQueue.add(0, w);
                    }
                } else {
                    if (postAtFront) {
                        mQueue.add(0, w);
                    } else {
                        mQueue.add(w);
                    }
                    mQueue.notifyAll();
                }
            }
            if (false) {
                dumpQueue("+" + (postAtFront ? "F " : "B ") + tag + ": ");
            }
        }
        return null;
    }

    private void dumpQueue(String s) {
        synchronized (mQueue) {
            StringBuilder sb = new StringBuilder(s);
            for (int i = 0; i < mQueue.size(); i++) {
                sb.append(mQueue.get(i).mTag + " ");
            }
        }
    }

    long bitmapSize(Bitmap b) {
        return b.getWidth() * b.getHeight() * 4;
    }

    class WorkItem {
        IImage mImage;
        int mTargetX;
        int mTargetY;
        int mTag;
        LoadedCallback mOnLoadedRunnable;
        boolean mPostBack;

        WorkItem(IImage image, int tag, LoadedCallback onLoadedRunnable,
                 boolean postBack) {
            mImage = image;
            mTag = tag;
            mOnLoadedRunnable = onLoadedRunnable;
            mPostBack = postBack;
        }

        @Override
        public boolean equals(Object other) {
            WorkItem otherWorkItem = (WorkItem) other;
            return otherWorkItem.mImage == mImage;
        }

        @Override
        public int hashCode() {
            return mImage.fullSizeImageUri().hashCode();
        }
    }

    public ImageLoader(android.os.Handler handler, int threadCount) {
        mThreadCount = threadCount;
        mHandler = handler;
        start();
    }

    private synchronized void start() {
        synchronized (mDecodeThreads) {
            if (mDecodeThreads.size() > 0) {
                return;
            }

            mDone = false;
            for (int i = 0; i < mThreadCount; i++) {
                Thread t = new Thread(new Runnable() {
                    // pick off items on the queue, one by one, and compute
                    // their bitmap. place the resulting bitmap in the cache.
                    // then post a notification back to the ui so things can
                    // get updated appropriately.
                    public void run() {
                        while (!mDone) {
                            WorkItem workItem = null;
                            synchronized (mQueue) {
                                if (mQueue.size() > 0) {
                                    workItem = mQueue.remove(0);
                                    mInProgress.add(workItem);
                                } else {
                                    try {
                                        mQueue.wait();
                                    } catch (InterruptedException ex) {
                                        // ignore the exception
                                    }
                                }
                            }
                            if (workItem != null) {
                                if (false) {
                                    dumpQueue("-" + workItem.mTag + ": ");
                                }
                                Bitmap b = workItem.mImage.miniThumbBitmap();

                                synchronized (mQueue) {
                                    mInProgress.remove(workItem);
                                }

                                if (workItem.mOnLoadedRunnable != null) {
                                    if (workItem.mPostBack) {
                                        if (!mDone) {
                                            final WorkItem w1 = workItem;
                                            final Bitmap bitmap = b;
                                            mHandler.post(new Runnable() {
                                                public void run() {
                                                    w1.mOnLoadedRunnable
                                                            .run(bitmap);
                                                }
                                            });
                                        }
                                    } else {
                                        workItem.mOnLoadedRunnable.run(b);
                                    }
                                }
                            }
                        }
                    }
                });
                t.setName("image-loader-" + i);
                BitmapManager.instance().allowThreadDecoding(t);
                mDecodeThreads.add(t);
                t.start();
            }
        }
    }

    public void stop() {
        mDone = true;
        synchronized (mQueue) {
            mQueue.notifyAll();
        }
        while (mDecodeThreads.size() > 0) {
            Thread t = mDecodeThreads.get(0);
            try {
                BitmapManager.instance().cancelThreadDecoding(t);
                t.join();
                mDecodeThreads.remove(0);
            } catch (InterruptedException ex) {
                // so now what?
            }
        }
    }
}
