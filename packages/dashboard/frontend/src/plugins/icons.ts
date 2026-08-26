// oh-vue-icons registration. The library is tree-shaken: only the icons
// imported and passed to addIcons() here ship in the bundle.
//
// Scope, deliberately narrow (see public/icons/README.md for why app-tile
// logos stay as full-colour bundled SVGs rather than moving here):
//   - UI / badge glyphs: the Docker whale and a stacked-layers mark for
//     DockerBadge (there is no official Docker Compose logo, so a layers
//     glyph reads better than forcing the whale for a multi-service stack).
//   - A small set of Simple Icons brand marks used ONLY as a fallback in
//     AppIcon, for a package that declares a known slug but ships no
//     bundled SVG. Most apps have an SVG, so this rarely fires; it exists
//     so a missing file degrades to a real logo before the initial tile.
import { OhVueIcon, addIcons } from 'oh-vue-icons';
import {
  SiDocker,
  FaLayerGroup,
  SiJellyfin,
  SiGrafana,
  SiAdguard,
  SiHomeassistant,
  SiRoundcube,
  SiNextcloud,
} from 'oh-vue-icons/icons';

addIcons(
  SiDocker,
  FaLayerGroup,
  SiJellyfin,
  SiGrafana,
  SiAdguard,
  SiHomeassistant,
  SiRoundcube,
  SiNextcloud,
);

export { OhVueIcon };
