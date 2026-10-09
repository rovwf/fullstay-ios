//
//  BrowserViewController+FullStay.swift
//  Reynard (FullStay)
//
//  Fullscreen browsing: the toolbars, status bar and home indicator are hidden and stay hidden when you
//  leave the app and come back, unless this phone's switch on rovwf.com says "leave fullscreen on return".
//  It also covers a page's own fullscreen (videos, games): if that ends because you left the app,
//  FullStay keeps the app in fullscreen instead, so nothing visibly changes when you come back.
//
//  Turn it on from the page menu (the "..." in the address bar) > Fullscreen.
//  Leave it with a two-finger double tap anywhere on the page.
//

import GeckoView
import UIKit

extension BrowserViewController {
    /// The browser that actually shows pages (on iPad with the sidebar, the root is only a host for it).
    var fullStayBrowser: BrowserViewController {
        return (sidebarCoordinator.contentBrowser as? BrowserViewController) ?? self
    }

    // MARK: - Start

    /// Called once from viewDidLoad.
    func startFullStay() {
        let tap = UITapGestureRecognizer(target: self, action: #selector(fullStayTwoFingerDoubleTap(_:)))
        tap.numberOfTouchesRequired = 2
        tap.numberOfTapsRequired = 2
        tap.cancelsTouchesInView = false
        tap.delaysTouchesEnded = false
        tap.delegate = FullStayGestureDelegate.shared
        view.addGestureRecognizer(tap)

        // iOS may have closed the app while you were away: come back the way you left it.
        if FullStay.savedFullscreen && !FullStay.exitOnReturn {
            setFullStayFullscreen(true, showHint: false)
        }
        FullStayRemote.shared.refresh(force: true)              // signs this phone up the first time
    }

    // MARK: - On and off

    func setFullStayFullscreen(_ on: Bool, showHint: Bool = true) {
        if on {
            if tabOverview.isPresented {
                tabOverview.setPresented(false, animated: false)
            }
            searchOverlayCoordinator.setFocused(false, animated: false)
            view.endEditing(true)
        }
        fullStayFullscreen = on
        FullStay.savedFullscreen = on
        if on && showHint {
            showFullStayHintIfNeeded()
        }
    }

    /// Runs whenever `fullStayFullscreen` changes.
    func fullStayFullscreenDidChange() {
        if !isShowingFullscreenMedia {
            sidebarCoordinator.setFullscreen(fullStayFullscreen)
        }
        setNeedsStatusBarAppearanceUpdate()
        setNeedsUpdateOfHomeIndicatorAutoHidden()
        setNeedsUpdateOfScreenEdgesDeferringSystemGestures()
        updateBrowserLayout(animated: false)
    }

    @objc func fullStayTwoFingerDoubleTap(_ recognizer: UITapGestureRecognizer) {
        guard recognizer.state == .ended, fullStayFullscreen, !isShowingFullscreenMedia else {
            return
        }
        setFullStayFullscreen(false)
    }

    /// Leaves every kind of fullscreen: FullStay's and the page's own.
    func leaveAllFullscreen() {
        exitFullscreenIfNeeded()
        setFullStayFullscreen(false)
    }

    // MARK: - Leaving the app and coming back (called by the scene delegate)

    func fullStayDidEnterBackground() {
        fullStayState.isAway = true
        FullStayRemote.shared.refreshWhileLeaving()              // so the switch is fresh when you come back
    }

    func fullStayWillEnterForeground() {
        let wasAway = fullStayState.isAway
        fullStayState.isAway = false
        fullStayState.returnedAt = Date()
        guard wasAway else { return }
        if FullStay.exitOnReturn {
            leaveAllFullscreen()
            return
        }
        // Still ask: if the switch was just turned off, apply it right away (only moments after coming back).
        FullStayRemote.shared.refresh { [weak self] exit in
            guard let self, exit, let back = self.fullStayState.returnedAt, Date().timeIntervalSince(back) < 5 else {
                return
            }
            self.leaveAllFullscreen()
        }
    }

    /// Called at the start of applyFullscreenState: a page's own fullscreen is about to start or end.
    func fullStayPageFullscreenWillChange(to fullScreen: Bool) {
        guard !fullScreen, isShowingFullscreenMedia else { return }
        // Ended because you left the app (or iOS ended it just as you came back)? Keep the app fullscreen.
        if fullStayState.isNearAppSwitch && !FullStay.exitOnReturn {
            setFullStayFullscreen(true, showHint: false)
        }
    }

    // MARK: - Hint

    private func showFullStayHintIfNeeded() {
        guard FullStay.hintCount < 3 else { return }
        FullStay.hintCount += 1
        let label = UILabel()
        label.text = NSLocalizedString("Two-finger double tap to leave fullscreen", comment: "FullStay hint")
        label.font = .systemFont(ofSize: 15, weight: .medium)
        label.textColor = .white
        label.textAlignment = .center
        label.numberOfLines = 0
        label.backgroundColor = UIColor.black.withAlphaComponent(0.75)
        label.layer.cornerRadius = 14
        label.layer.masksToBounds = true
        label.alpha = 0
        label.translatesAutoresizingMaskIntoConstraints = false
        label.isUserInteractionEnabled = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -40),
            label.widthAnchor.constraint(lessThanOrEqualTo: view.widthAnchor, constant: -48),
            label.heightAnchor.constraint(greaterThanOrEqualToConstant: 44),
        ])
        // Inner padding without a container view.
        label.text = "   " + (label.text ?? "") + "   "
        UIView.animate(withDuration: 0.25, animations: { label.alpha = 1 }, completion: { _ in
            UIView.animate(withDuration: 0.4, delay: 2.6, options: [], animations: { label.alpha = 0 }, completion: { _ in
                label.removeFromSuperview()
            })
        })
    }
}
