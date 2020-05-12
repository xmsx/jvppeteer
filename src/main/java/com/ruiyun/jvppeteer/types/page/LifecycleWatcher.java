package com.ruiyun.jvppeteer.types.page;

import com.ruiyun.jvppeteer.events.impl.BrowserListenerWrapper;
import com.ruiyun.jvppeteer.events.definition.Events;
import com.ruiyun.jvppeteer.events.impl.DefaultBrowserListener;
import com.ruiyun.jvppeteer.exception.TerminateException;
import com.ruiyun.jvppeteer.util.Helper;
import com.ruiyun.jvppeteer.util.ValidateUtil;

import java.util.ArrayList;
import java.util.List;

public class LifecycleWatcher {

    private List<String> expectedLifecycle = new ArrayList<>();

    private FrameManager frameManager;

    private Frame frame;

    private int timeout;

    private Request navigationRequest;

    private List<BrowserListenerWrapper> eventListeners;

    private String initialLoaderId;

    private boolean hasSameDocumentNavigation;


    public LifecycleWatcher() {
        super();
    }

    public LifecycleWatcher(FrameManager frameManager, Frame frame, List<String> waitUntil, int timeout) {
        super();
        this.frameManager = frameManager;
        this.frame = frame;
        this.initialLoaderId = frame.getLoaderId();
        this.timeout = timeout;
        this.navigationRequest = null;
        waitUntil.replaceAll(value -> {
            if ("domcontentloaded".equals(value)) {
                return "DOMContentLoaded";
            } else if ("networkidle0".equals(value)) {
                return "networkIdle";
            } else if ("networkidle2".equals(value)) {
                return "networkAlmostIdle";
            } else if ("load".equals(value)) {
                return "load";
            }
            throw new IllegalArgumentException("Unknown value for options.waitUntil: " + value);
        });
        this.expectedLifecycle.addAll(waitUntil);

        this.eventListeners = new ArrayList<>();

        DefaultBrowserListener<Object> disconnecteListener = new DefaultBrowserListener<Object>() {
            @Override
            public void onBrowserEvent(Object event) {
                LifecycleWatcher watcher = (LifecycleWatcher) this.getTarget();
                watcher.terminate(new TerminateException("Navigation failed because browser has disconnected!"));
            }
        };
        disconnecteListener.setTarget(this);
        disconnecteListener.setMothod(Events.CDPSESSION_DISCONNECTED.getName());

        DefaultBrowserListener<Object> lifecycleEventListener = new DefaultBrowserListener<Object>() {
            @Override
            public void onBrowserEvent(Object event) {
                LifecycleWatcher watcher = (LifecycleWatcher) this.getTarget();
                watcher.checkLifecycleComplete();
            }
        };
        lifecycleEventListener.setTarget(this);
        lifecycleEventListener.setMothod(Events.FRAME_MANAGER_LIFECYCLE_EVENT.getName());

        DefaultBrowserListener<Frame> documentListener = new DefaultBrowserListener<Frame>() {
            @Override
            public void onBrowserEvent(Frame event) {
                LifecycleWatcher watcher = (LifecycleWatcher) this.getTarget();
                watcher.navigatedWithinDocument(event);
            }
        };
        documentListener.setTarget(this);
        documentListener.setMothod(Events.FRAME_MANAGER_FRAME_NAVIGATED_WITHIN_DOCUMENT.getName());

        DefaultBrowserListener<Frame> detachedListener = new DefaultBrowserListener<Frame>() {
            @Override
            public void onBrowserEvent(Frame event) {
                LifecycleWatcher watcher = (LifecycleWatcher) this.getTarget();
                watcher.onFrameDetached(event);
            }
        };
        detachedListener.setTarget(this);
        detachedListener.setMothod(Events.FRAME_MANAGER_FRAME_DETACHED.getName());

        DefaultBrowserListener<Request> requestListener = new DefaultBrowserListener<Request>() {
            @Override
            public void onBrowserEvent(Request event) {
                LifecycleWatcher watcher = (LifecycleWatcher) this.getTarget();
                watcher.onRequest(event);
            }
        };
        requestListener.setTarget(this);
        requestListener.setMothod(Events.NETWORK_MANAGER_REQUEST.getName());
        eventListeners.add(Helper.addEventListener(this.frameManager.getClient(), disconnecteListener.getMothod(), disconnecteListener));
        eventListeners.add(Helper.addEventListener(this.frameManager, lifecycleEventListener.getMothod(), lifecycleEventListener));
        eventListeners.add(Helper.addEventListener(frameManager, documentListener.getMothod(), documentListener));
        eventListeners.add(Helper.addEventListener(frameManager, detachedListener.getMothod(), detachedListener));
        eventListeners.add(Helper.addEventListener(frameManager.getNetworkManager(), requestListener.getMothod(), requestListener));
        this.checkLifecycleComplete();
    }

    public void lifecyclePromise() {
    }

    public void lifecycleCallback() {
        setNavigateResult("success");
    }

    private void onFrameDetached(Frame frame) {
        if (this.frame.equals(frame)) {
            terminationCallback();
            return;
        }
        this.checkLifecycleComplete();
    }

    private void onRequest(Request request) {
        if (request.getFrame() != this.frame || !request.getIsNavigationRequest())
            return;
        this.navigationRequest = request;
    }

    private void navigatedWithinDocument(Frame frame) {
        if (this.frame != frame)
            return;
        this.hasSameDocumentNavigation = true;
        this.checkLifecycleComplete();
    }

    private void checkLifecycleComplete() {
        // We expect navigation to commit.
        if (!checkLifecycle(this.frame, this.expectedLifecycle)) return;
        this.lifecycleCallback();
        if (this.frame.getLoaderId().equals(this.initialLoaderId) && !this.hasSameDocumentNavigation)
            return;
        if (this.hasSameDocumentNavigation)
            this.sameDocumentNavigationCompleteCallback();
        if (this.frame.getLoaderId().equals(this.initialLoaderId))
            this.newDocumentNavigationCompleteCallback();
    }
    /**
     * @param {!Frame} frame
     * @param {!Array<string>} expectedLifecycle
     * @return {boolean}
     */
    private boolean checkLifecycle(Frame frame, List<String> expectedLifecycle) {
        if(ValidateUtil.isNotEmpty(expectedLifecycle)){
            for (String event : expectedLifecycle) {
                if (!frame.getLifecycleEvents().contains(event)) return false;
            }
        }
        if(ValidateUtil.isNotEmpty(frame.childFrames())){
            for (Frame child : frame.childFrames()) {
                if (!checkLifecycle(child, expectedLifecycle)) return false;
            }
        }
        return true;
    }

    private void terminate(TerminateException e) {
        terminationCallback();
    }

    public void terminationCallback() {
        setNavigateResult("termination");
    }

    public String createTimeoutPromise() {

        return null;
    }

    public void dispose() {
        Helper.removeEventListeners(this.eventListeners);
    }

    public Response navigationResponse() {
        return this.navigationRequest != null ? this.navigationRequest.response() : null;
    }

    public void newDocumentNavigationCompleteCallback() {
        if ("new".equals(this.frameManager.getDocumentNavigationPromiseType()) || "all".equals(this.frameManager.getDocumentNavigationPromiseType()))
            setNavigateResult("success");
    }
    public void sameDocumentNavigationCompleteCallback() {
        if ("same".equals(this.frameManager.getDocumentNavigationPromiseType()) || "all".equals(this.frameManager.getDocumentNavigationPromiseType()))
            setNavigateResult("success");
    }

    public void sameDocumentNavigationPromise() {
        setNavigateResult("success");
    }
    private void setNavigateResult(String result) {
        if (this.frameManager.getLatch() != null && this.frameManager.getLatch().getCount() > 0) {
            this.frameManager.setNavigateResult(result);
            this.frameManager.getLatch().countDown();
        }
    }

}
