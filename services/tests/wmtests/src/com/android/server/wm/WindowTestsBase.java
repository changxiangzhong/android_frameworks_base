/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.AppOpsManager.OP_NONE;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.os.Process.SYSTEM_UID;
import static android.view.View.VISIBLE;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.mockito.Mockito.mock;

import android.content.Context;
import android.util.Log;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.IWindow;
import android.view.SurfaceControl.Transaction;
import android.view.WindowManager;

import com.android.server.AttributeCache;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;

import java.util.HashSet;
import java.util.LinkedList;

/**
 * Common base class for window manager unit test classes.
 *
 * Make sure any requests to WM hold the WM lock if needed b/73966377
 */
class WindowTestsBase extends SystemServiceTestsBase {
    private static final String TAG = WindowTestsBase.class.getSimpleName();

    WindowManagerService mWm;
    private final IWindow mIWindow = new TestIWindow();
    private Session mMockSession;
    static int sNextStackId = 1000;

    /** Non-default display. */
    DisplayContent mDisplayContent;
    DisplayInfo mDisplayInfo = new DisplayInfo();
    WindowState mWallpaperWindow;
    WindowState mImeWindow;
    WindowState mImeDialogWindow;
    WindowState mStatusBarWindow;
    WindowState mDockedDividerWindow;
    WindowState mNavBarWindow;
    WindowState mAppWindow;
    WindowState mChildAppWindowAbove;
    WindowState mChildAppWindowBelow;
    HashSet<WindowState> mCommonWindows;

    /**
     * Spied {@link Transaction} class than can be used to verify calls.
     */
    Transaction mTransaction;

    @BeforeClass
    public static void setUpOnceBase() {
        AttributeCache.init(getInstrumentation().getTargetContext());
    }

    @Before
    public void setUpBase() {
        // If @Before throws an exception, the error isn't logged. This will make sure any failures
        // in the set up are clear. This can be removed when b/37850063 is fixed.
        try {
            mMockSession = mock(Session.class);

            final Context context = getInstrumentation().getTargetContext();

            mWm = mSystemServicesTestRule.getWindowManagerService();
            mTransaction = mSystemServicesTestRule.mTransaction;

            beforeCreateDisplay();

            context.getDisplay().getDisplayInfo(mDisplayInfo);
            mDisplayContent = createNewDisplay();

            // Set-up some common windows.
            mCommonWindows = new HashSet<>();
            synchronized (mWm.mGlobalLock) {
                mWallpaperWindow = createCommonWindow(null, TYPE_WALLPAPER, "wallpaperWindow");
                mImeWindow = createCommonWindow(null, TYPE_INPUT_METHOD, "mImeWindow");
                mDisplayContent.mInputMethodWindow = mImeWindow;
                mImeDialogWindow = createCommonWindow(null, TYPE_INPUT_METHOD_DIALOG,
                        "mImeDialogWindow");
                mStatusBarWindow = createCommonWindow(null, TYPE_STATUS_BAR, "mStatusBarWindow");
                mNavBarWindow = createCommonWindow(null, TYPE_NAVIGATION_BAR, "mNavBarWindow");
                mDockedDividerWindow = createCommonWindow(null, TYPE_DOCK_DIVIDER,
                        "mDockedDividerWindow");
                mAppWindow = createCommonWindow(null, TYPE_BASE_APPLICATION, "mAppWindow");
                mChildAppWindowAbove = createCommonWindow(mAppWindow,
                        TYPE_APPLICATION_ATTACHED_DIALOG,
                        "mChildAppWindowAbove");
                mChildAppWindowBelow = createCommonWindow(mAppWindow,
                        TYPE_APPLICATION_MEDIA_OVERLAY,
                        "mChildAppWindowBelow");
            }
            // Adding a display will cause freezing the display. Make sure to wait until it's
            // unfrozen to not run into race conditions with the tests.
            waitUntilHandlersIdle();
        } catch (Exception e) {
            Log.e(TAG, "Failed to set up test", e);
            throw e;
        }
    }

    void beforeCreateDisplay() {
        // Called before display is created.
    }

    @After
    public void tearDownBase() {
        // If @After throws an exception, the error isn't logged. This will make sure any failures
        // in the tear down are clear. This can be removed when b/37850063 is fixed.
        try {
            // Test may schedule to perform surface placement or other messages. Wait until a
            // stable state to clean up for consistency.
            waitUntilHandlersIdle();

            final LinkedList<WindowState> nonCommonWindows = new LinkedList<>();

            synchronized (mWm.mGlobalLock) {
                mWm.mRoot.forAllWindows(w -> {
                    if (!mCommonWindows.contains(w)) {
                        nonCommonWindows.addLast(w);
                    }
                }, true /* traverseTopToBottom */);

                while (!nonCommonWindows.isEmpty()) {
                    nonCommonWindows.pollLast().removeImmediately();
                }

                // Remove app transition & window freeze timeout callbacks to prevent unnecessary
                // actions after test.
                mWm.getDefaultDisplayContentLocked().mAppTransition
                        .removeAppTransitionTimeoutCallbacks();
                mWm.mH.removeMessages(WindowManagerService.H.WINDOW_FREEZE_TIMEOUT);
                mDisplayContent.mInputMethodTarget = null;
            }

            // Cleaned up everything in Handler.
            cleanupWindowManagerHandlers();
        } catch (Exception e) {
            Log.e(TAG, "Failed to tear down test", e);
            throw e;
        }
    }

    private WindowState createCommonWindow(WindowState parent, int type, String name) {
        synchronized (mWm.mGlobalLock) {
            final WindowState win = createWindow(parent, type, name);
            mCommonWindows.add(win);
            // Prevent common windows from been IMe targets
            win.mAttrs.flags |= FLAG_NOT_FOCUSABLE;
            return win;
        }
    }

    private WindowToken createWindowToken(
            DisplayContent dc, int windowingMode, int activityType, int type) {
        synchronized (mWm.mGlobalLock) {
            if (type < FIRST_APPLICATION_WINDOW || type > LAST_APPLICATION_WINDOW) {
                return WindowTestUtils.createTestWindowToken(type, dc);
            }

            return createActivityRecord(dc, windowingMode, activityType);
        }
    }

    ActivityRecord createActivityRecord(DisplayContent dc, int windowingMode, int activityType) {
        return createTestActivityRecord(dc, windowingMode, activityType);
    }

    ActivityRecord createTestActivityRecord(DisplayContent dc, int
            windowingMode, int activityType) {
        final ActivityStack stack = createTaskStackOnDisplay(windowingMode, activityType, dc);
        return WindowTestUtils.createTestActivityRecord(stack);
    }

    WindowState createWindow(WindowState parent, int type, String name) {
        synchronized (mWm.mGlobalLock) {
            return (parent == null)
                    ? createWindow(parent, type, mDisplayContent, name)
                    : createWindow(parent, type, parent.mToken, name);
        }
    }

    WindowState createWindow(WindowState parent, int type, String name, int ownerId) {
        synchronized (mWm.mGlobalLock) {
            return (parent == null)
                    ? createWindow(parent, type, mDisplayContent, name, ownerId)
                    : createWindow(parent, type, parent.mToken, name, ownerId);
        }
    }

    WindowState createWindowOnStack(WindowState parent, int windowingMode, int activityType,
            int type, DisplayContent dc, String name) {
        synchronized (mWm.mGlobalLock) {
            final WindowToken token = createWindowToken(dc, windowingMode, activityType, type);
            return createWindow(parent, type, token, name);
        }
    }

    WindowState createAppWindow(Task task, int type, String name) {
        synchronized (mWm.mGlobalLock) {
            final ActivityRecord activity =
                    WindowTestUtils.createTestActivityRecord(mDisplayContent);
            task.addChild(activity, 0);
            return createWindow(null, type, activity, name);
        }
    }

    WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name) {
        synchronized (mWm.mGlobalLock) {
            final WindowToken token = createWindowToken(
                    dc, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, type);
            return createWindow(parent, type, token, name, 0 /* ownerId */);
        }
    }

    WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name,
            int ownerId) {
        synchronized (mWm.mGlobalLock) {
            final WindowToken token = createWindowToken(
                    dc, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, type);
            return createWindow(parent, type, token, name, ownerId);
        }
    }

    WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name,
            boolean ownerCanAddInternalSystemWindow) {
        synchronized (mWm.mGlobalLock) {
            final WindowToken token = createWindowToken(
                    dc, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, type);
            return createWindow(parent, type, token, name, 0 /* ownerId */,
                    ownerCanAddInternalSystemWindow);
        }
    }

    WindowState createWindow(WindowState parent, int type, WindowToken token, String name) {
        synchronized (mWm.mGlobalLock) {
            return createWindow(parent, type, token, name, 0 /* ownerId */,
                    false /* ownerCanAddInternalSystemWindow */);
        }
    }

    WindowState createWindow(WindowState parent, int type, WindowToken token, String name,
            int ownerId) {
        synchronized (mWm.mGlobalLock) {
            return createWindow(parent, type, token, name, ownerId,
                    false /* ownerCanAddInternalSystemWindow */);
        }
    }

    WindowState createWindow(WindowState parent, int type, WindowToken token, String name,
            int ownerId, boolean ownerCanAddInternalSystemWindow) {
        return createWindow(parent, type, token, name, ownerId, ownerCanAddInternalSystemWindow,
                mWm, mMockSession, mIWindow, mSystemServicesTestRule.getPowerManagerWrapper());
    }

    static WindowState createWindow(WindowState parent, int type, WindowToken token,
            String name, int ownerId, boolean ownerCanAddInternalSystemWindow,
            WindowManagerService service, Session session, IWindow iWindow,
            WindowState.PowerManagerWrapper powerManagerWrapper) {
        synchronized (service.mGlobalLock) {
            final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(type);
            attrs.setTitle(name);

            final WindowState w = new WindowState(service, session, iWindow, token, parent,
                    OP_NONE,
                    0, attrs, VISIBLE, ownerId, ownerCanAddInternalSystemWindow,
                    powerManagerWrapper);
            // TODO: Probably better to make this call in the WindowState ctor to avoid errors with
            // adding it to the token...
            token.addWindow(w);
            return w;
        }
    }

    /** Creates a {@link ActivityStack} and adds it to the specified {@link DisplayContent}. */
    ActivityStack createTaskStackOnDisplay(DisplayContent dc) {
        return createTaskStackOnDisplay(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, dc);
    }

    ActivityStack createTaskStackOnDisplay(int windowingMode, int activityType, DisplayContent dc) {
        synchronized (mWm.mGlobalLock) {
            return new ActivityTestsBase.StackBuilder(
                    dc.mWmService.mAtmService.mRootActivityContainer)
                    .setDisplay(dc)
                    .setWindowingMode(windowingMode)
                    .setActivityType(activityType)
                    .setCreateActivity(false)
                    .build();
        }
    }

    /** Creates a {@link Task} and adds it to the specified {@link ActivityStack}. */
    Task createTaskInStack(ActivityStack stack, int userId) {
        return WindowTestUtils.createTaskInStack(mWm, stack, userId);
    }

    /** Creates a {@link DisplayContent} and adds it to the system. */
    DisplayContent createNewDisplay() {
        return createNewDisplay(mDisplayInfo);
    }

    /** Creates a {@link DisplayContent} and adds it to the system. */
    DisplayContent createNewDisplay(DisplayInfo info) {
        final ActivityDisplay display =
                new TestActivityDisplay.Builder(mWm.mAtmService, info).build();
        return display.mDisplayContent;
    }

    /**
     * Creates a {@link DisplayContent} with given display state and adds it to the system.
     *
     * @param displayState For initializing the state of the display. See
     *                     {@link Display#getState()}.
     */
    DisplayContent createNewDisplay(int displayState) {
        // Leverage main display info & initialize it with display state for given displayId.
        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.copyFrom(mDisplayInfo);
        displayInfo.state = displayState;
        return createNewDisplay(displayInfo);
    }

    /** Creates a {@link com.android.server.wm.WindowTestUtils.TestWindowState} */
    WindowTestUtils.TestWindowState createWindowState(WindowManager.LayoutParams attrs,
            WindowToken token) {
        synchronized (mWm.mGlobalLock) {
            return new WindowTestUtils.TestWindowState(mWm, mMockSession, mIWindow, attrs, token);
        }
    }

    /** Creates a {@link DisplayContent} as parts of simulate display info for test. */
    DisplayContent createMockSimulatedDisplay() {
        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.copyFrom(mDisplayInfo);
        displayInfo.type = Display.TYPE_VIRTUAL;
        displayInfo.ownerUid = SYSTEM_UID;
        return createNewDisplay(displayInfo);
    }

    /** Sets the default minimum task size to 1 so that tests can use small task sizes */
    void removeGlobalMinSizeRestriction() {
        mWm.mAtmService.mRootActivityContainer.mDefaultMinSizeOfResizeableTaskDp = 1;
    }
}
