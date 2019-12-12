/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import android.app.ActivityOptions;
import android.app.IApplicationThread;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.UserHandle;
import android.service.voice.IVoiceInteractionSession;

import com.android.server.AttributeCache;

import org.junit.Before;
import org.junit.BeforeClass;

/**
 * A base class to handle common operations in activity related unit tests.
 */
class ActivityTestsBase extends SystemServiceTestsBase {
    final Context mContext = getInstrumentation().getTargetContext();

    ActivityTaskManagerService mService;
    RootActivityContainer mRootActivityContainer;
    ActivityStackSupervisor mSupervisor;

    // Default package name
    static final String DEFAULT_COMPONENT_PACKAGE_NAME = "com.foo";

    // Default base activity name
    private static final String DEFAULT_COMPONENT_CLASS_NAME = ".BarActivity";

    @BeforeClass
    public static void setUpOnceBase() {
        AttributeCache.init(getInstrumentation().getTargetContext());
    }

    @Before
    public void setUpBase() {
        mService = mSystemServicesTestRule.getActivityTaskManagerService();
        mSupervisor = mService.mStackSupervisor;
        mRootActivityContainer = mService.mRootActivityContainer;
    }

    /** Creates and adds a {@link TestActivityDisplay} to supervisor at the given position. */
    TestActivityDisplay addNewActivityDisplayAt(int position) {
        return new TestActivityDisplay.Builder(mService, 1000, 1500).setPosition(position).build();
    }

    /** Sets the default minimum task size to 1 so that tests can use small task sizes */
    public void removeGlobalMinSizeRestriction() {
        mService.mRootActivityContainer.mDefaultMinSizeOfResizeableTaskDp = 1;
    }

    /**
     * Builder for creating new activities.
     */
    protected static class ActivityBuilder {
        // An id appended to the end of the component name to make it unique
        private static int sCurrentActivityId = 0;

        private final ActivityTaskManagerService mService;

        private ComponentName mComponent;
        private String mTargetActivity;
        private Task mTask;
        private String mProcessName = "name";
        private int mUid = 12345;
        private boolean mCreateTask;
        private ActivityStack mStack;
        private int mActivityFlags;
        private int mLaunchMode;
        private int mResizeMode = RESIZE_MODE_RESIZEABLE;
        private float mMaxAspectRatio;
        private int mScreenOrientation = SCREEN_ORIENTATION_UNSPECIFIED;
        private boolean mLaunchTaskBehind;
        private int mConfigChanges;
        private int mLaunchedFromPid;
        private int mLaunchedFromUid;

        ActivityBuilder(ActivityTaskManagerService service) {
            mService = service;
        }

        ActivityBuilder setComponent(ComponentName component) {
            mComponent = component;
            return this;
        }

        ActivityBuilder setTargetActivity(String targetActivity) {
            mTargetActivity = targetActivity;
            return this;
        }

        static ComponentName getDefaultComponent() {
            return ComponentName.createRelative(DEFAULT_COMPONENT_PACKAGE_NAME,
                    DEFAULT_COMPONENT_PACKAGE_NAME);
        }

        ActivityBuilder setTask(Task task) {
            mTask = task;
            return this;
        }

        ActivityBuilder setActivityFlags(int flags) {
            mActivityFlags = flags;
            return this;
        }

        ActivityBuilder setLaunchMode(int launchMode) {
            mLaunchMode = launchMode;
            return this;
        }

        ActivityBuilder setStack(ActivityStack stack) {
            mStack = stack;
            return this;
        }

        ActivityBuilder setCreateTask(boolean createTask) {
            mCreateTask = createTask;
            return this;
        }

        ActivityBuilder setProcessName(String name) {
            mProcessName = name;
            return this;
        }

        ActivityBuilder setUid(int uid) {
            mUid = uid;
            return this;
        }

        ActivityBuilder setResizeMode(int resizeMode) {
            mResizeMode = resizeMode;
            return this;
        }

        ActivityBuilder setMaxAspectRatio(float maxAspectRatio) {
            mMaxAspectRatio = maxAspectRatio;
            return this;
        }

        ActivityBuilder setScreenOrientation(int screenOrientation) {
            mScreenOrientation = screenOrientation;
            return this;
        }

        ActivityBuilder setLaunchTaskBehind(boolean launchTaskBehind) {
            mLaunchTaskBehind = launchTaskBehind;
            return this;
        }

        ActivityBuilder setConfigChanges(int configChanges) {
            mConfigChanges = configChanges;
            return this;
        }

        ActivityBuilder setLaunchedFromPid(int pid) {
            mLaunchedFromPid = pid;
            return this;
        }

        ActivityBuilder setLaunchedFromUid(int uid) {
            mLaunchedFromUid = uid;
            return this;
        }

        ActivityRecord build() {
            try {
                mService.deferWindowLayout();
                return buildInner();
            } finally {
                mService.continueWindowLayout();
            }
        }

        ActivityRecord buildInner() {
            if (mComponent == null) {
                final int id = sCurrentActivityId++;
                mComponent = ComponentName.createRelative(DEFAULT_COMPONENT_PACKAGE_NAME,
                        DEFAULT_COMPONENT_CLASS_NAME + id);
            }

            if (mCreateTask) {
                mTask = new TaskBuilder(mService.mStackSupervisor)
                        .setComponent(mComponent)
                        .setStack(mStack).build();
            }

            Intent intent = new Intent();
            intent.setComponent(mComponent);
            final ActivityInfo aInfo = new ActivityInfo();
            aInfo.applicationInfo = new ApplicationInfo();
            aInfo.applicationInfo.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;
            aInfo.applicationInfo.packageName = mComponent.getPackageName();
            aInfo.applicationInfo.uid = mUid;
            aInfo.processName = mProcessName;
            aInfo.packageName = mComponent.getPackageName();
            if (mTargetActivity != null) {
                aInfo.targetActivity = mTargetActivity;
            }
            aInfo.flags |= mActivityFlags;
            aInfo.launchMode = mLaunchMode;
            aInfo.resizeMode = mResizeMode;
            aInfo.maxAspectRatio = mMaxAspectRatio;
            aInfo.screenOrientation = mScreenOrientation;
            aInfo.configChanges |= mConfigChanges;

            ActivityOptions options = null;
            if (mLaunchTaskBehind) {
                options = ActivityOptions.makeTaskLaunchBehind();
            }

            final ActivityRecord activity = new ActivityRecord(mService, null /* caller */,
                    mLaunchedFromPid /* launchedFromPid */, mLaunchedFromUid /* launchedFromUid */,
                    null, intent, null, aInfo /*aInfo*/, new Configuration(), null /* resultTo */,
                    null /* resultWho */, 0 /* reqCode */, false /*componentSpecified*/,
                    false /* rootVoiceInteraction */, mService.mStackSupervisor, options,
                    null /* sourceRecord */);
            spyOn(activity);
            if (mTask != null) {
                // fullscreen value is normally read from resources in ctor, so for testing we need
                // to set it somewhere else since we can't mock resources.
                doReturn(true).when(activity).occludesParent();
                mTask.addChild(activity);
                // Make visible by default...
                activity.setVisible(true);
            }

            final WindowProcessController wpc = new WindowProcessController(mService,
                    mService.mContext.getApplicationInfo(), mProcessName, mUid,
                    UserHandle.getUserId(12345), mock(Object.class),
                    mock(WindowProcessListener.class));
            wpc.setThread(mock(IApplicationThread.class));
            activity.setProcess(wpc);
            doReturn(wpc).when(mService).getProcessController(
                    activity.processName, activity.info.applicationInfo.uid);

            // Resume top activities to make sure all other signals in the system are connected.
            mService.mRootActivityContainer.resumeFocusedStacksTopActivities();
            return activity;
        }
    }

    /**
     * Builder for creating new tasks.
     */
    protected static class TaskBuilder {
        private final ActivityStackSupervisor mSupervisor;

        private ComponentName mComponent;
        private String mPackage;
        private int mFlags = 0;
        // Task id 0 is reserved in ARC for the home app.
        private int mTaskId = SystemServicesTestRule.sNextTaskId++;
        private int mUserId = 0;
        private IVoiceInteractionSession mVoiceSession;
        private boolean mCreateStack = true;

        private ActivityStack mStack;

        TaskBuilder(ActivityStackSupervisor supervisor) {
            mSupervisor = supervisor;
        }

        TaskBuilder setComponent(ComponentName component) {
            mComponent = component;
            return this;
        }

        TaskBuilder setPackage(String packageName) {
            mPackage = packageName;
            return this;
        }

        /**
         * Set to {@code true} by default, set to {@code false} to prevent the task from
         * automatically creating a parent stack.
         */
        TaskBuilder setCreateStack(boolean createStack) {
            mCreateStack = createStack;
            return this;
        }

        TaskBuilder setVoiceSession(IVoiceInteractionSession session) {
            mVoiceSession = session;
            return this;
        }

        TaskBuilder setFlags(int flags) {
            mFlags = flags;
            return this;
        }

        TaskBuilder setTaskId(int taskId) {
            mTaskId = taskId;
            return this;
        }

        TaskBuilder setUserId(int userId) {
            mUserId = userId;
            return this;
        }

        TaskBuilder setStack(ActivityStack stack) {
            mStack = stack;
            return this;
        }

        Task build() {
            if (mStack == null && mCreateStack) {
                mStack = mSupervisor.mRootActivityContainer.getDefaultDisplay().createStack(
                        WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
                spyOn(mStack);
            }

            final ActivityInfo aInfo = new ActivityInfo();
            aInfo.applicationInfo = new ApplicationInfo();
            aInfo.applicationInfo.packageName = mPackage;

            Intent intent = new Intent();
            if (mComponent == null) {
                mComponent = ComponentName.createRelative(DEFAULT_COMPONENT_PACKAGE_NAME,
                        DEFAULT_COMPONENT_CLASS_NAME);
            }

            intent.setComponent(mComponent);
            intent.setFlags(mFlags);

            final Task task = new Task(mSupervisor.mService, mTaskId, aInfo,
                    intent /*intent*/, mVoiceSession, null /*_voiceInteractor*/,
                    null /*taskDescription*/, mStack);
            spyOn(task);
            task.mUserId = mUserId;

            if (mStack != null) {
                mStack.moveToFront("test");
                mStack.addChild(task, true, true);
            }

            return task;
        }
    }

    static class StackBuilder {
        private final RootActivityContainer mRootActivityContainer;
        private ActivityDisplay mDisplay;
        private int mStackId = -1;
        private int mWindowingMode = WINDOWING_MODE_UNDEFINED;
        private int mActivityType = ACTIVITY_TYPE_STANDARD;
        private boolean mOnTop = true;
        private boolean mCreateActivity = true;

        StackBuilder(RootActivityContainer root) {
            mRootActivityContainer = root;
            mDisplay = mRootActivityContainer.getDefaultDisplay();
        }

        StackBuilder setWindowingMode(int windowingMode) {
            mWindowingMode = windowingMode;
            return this;
        }

        StackBuilder setActivityType(int activityType) {
            mActivityType = activityType;
            return this;
        }

        StackBuilder setStackId(int stackId) {
            mStackId = stackId;
            return this;
        }

        // TODO(display-merge): Remove
        StackBuilder setDisplay(ActivityDisplay display) {
            mDisplay = display;
            return this;
        }

        StackBuilder setDisplay(DisplayContent display) {
            // TODO(display-merge): Remove cast
            mDisplay = (ActivityDisplay) display;
            return this;
        }

        StackBuilder setOnTop(boolean onTop) {
            mOnTop = onTop;
            return this;
        }

        StackBuilder setCreateActivity(boolean createActivity) {
            mCreateActivity = createActivity;
            return this;
        }

        ActivityStack build() {
            final int stackId = mStackId >= 0 ? mStackId : mDisplay.getNextStackId();
            final ActivityStack stack;
            final ActivityStackSupervisor supervisor = mRootActivityContainer.mStackSupervisor;
            if (mWindowingMode == WINDOWING_MODE_PINNED) {
                stack = new ActivityStack(mDisplay, stackId, supervisor,
                        mWindowingMode, ACTIVITY_TYPE_STANDARD, mOnTop);
            } else {
                stack = new ActivityStack(mDisplay, stackId, supervisor,
                        mWindowingMode, mActivityType, mOnTop);

                if (mCreateActivity) {
                    new ActivityBuilder(supervisor.mService)
                            .setCreateTask(true)
                            .setStack(stack)
                            .build();
                    if (mOnTop) {
                        // We move the task to front again in order to regain focus after activity
                        // added to the stack.
                        // Or {@link ActivityDisplay#mPreferredTopFocusableStack} could be other
                        // stacks (e.g. home stack).
                        stack.moveToFront("createActivityStack");
                    } else {
                        stack.moveToBack("createActivityStack", null);
                    }
                }
            }

            spyOn(stack);
            doNothing().when(stack).startActivityLocked(
                    any(), any(), anyBoolean(), anyBoolean(), any());

            return stack;
        }

    }
}
