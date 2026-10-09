//
//  FullStayKey.swift
//  Reynard (FullStay)
//
//  The sign-up key for rovwf.com (the same value as the Worker's FS_KEY secret).
//  Leave it empty here: the GitHub build ("FullStay iOS build" workflow) fills it in from the
//  repository secret FULLSTAY_KEY, so the key is never stored in the source.
//  Empty = remote control off: the app always stays in fullscreen.
//

enum FullStayKey {
    static let value = ""
}
