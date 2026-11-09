# Branding

- **App icon**: `app/src/modern/res/drawable/ic_launcher_{foreground,background}.xml` override upstream's icon for the
  modern flavour (no upstream file changed). The Thor's two screens: the GameNative mark on the top one, DUO drawn in
  the mark's style (round nodes, 13.92-thick strokes, rings of the G's radii) on the bottom one. Scaled to 0.58 so it
  survives a circular launcher mask.
- **Colours**: the logo gradient `#49D0EE → #5B8DF6 → #827EFB → #D74EE9` (cyan and blue first, purple last, the
  reverse of GameNative's). The app's accent is the logo blue `#5B8DF6` (`ui/theme/Color.kt`, `res/values/duo_colors.xml`
  in the modern flavour); purple `#D74EE9` is secondary.
- **Banner**: `banner.svg` (1280x640, also the GitHub social-preview size), rendered with
  `resvg --use-font-file Nunito-900.ttf --use-font-file Nunito-700.ttf banner.svg banner.png` (Nunito, SIL OFL, from
  Google Fonts). The PNG is `.github/assets/banner.png` on `master`.
