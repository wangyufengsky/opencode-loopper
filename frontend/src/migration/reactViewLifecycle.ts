/** A parent may retire one view only after that view's owners report ALLOW. */
export interface ReactViewLifecycle { disposeIfSafe(): boolean }
export interface ReactViewHost extends HTMLElement { readonly reactViewLifecycle?: Readonly<ReactViewLifecycle> }
