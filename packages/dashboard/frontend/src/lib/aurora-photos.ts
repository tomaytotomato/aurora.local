// Aurora hero photo metadata. Kept type-safe so we don't drift attribution.
// All photos: Wikimedia Commons, CC BY-SA 4.0 (or noted below), free to reuse
// with attribution. See ATTRIBUTIONS.md in this dir for full details.

export interface AuroraPhoto {
  slot: number;              // 1..7, matches /aurora/{slot}.jpg
  location: string;
  photographer: string;
  license: string;
  licenseUrl: string;
  sourceUrl: string;
}

export const AURORA_PHOTOS: AuroraPhoto[] = [
  {
    slot: 1,
    location: 'Lyngenfjorden, Norway',
    photographer: 'Simo Räsänen',
    license: 'CC BY-SA 3.0',
    licenseUrl: 'https://creativecommons.org/licenses/by-sa/3.0/',
    sourceUrl:
      'https://commons.wikimedia.org/wiki/File:Aurora_borealis_above_Lyngenfjorden,_2012_March.jpg',
  },
  {
    slot: 2,
    location: 'Iceland',
    photographer: 'Pixael (Giuseppe Milo)',
    license: 'CC BY 2.0',
    licenseUrl: 'https://creativecommons.org/licenses/by/2.0/',
    sourceUrl:
      'https://commons.wikimedia.org/wiki/File:The_Northern_Lights_Iceland_Travel_Photography_(209663839).jpeg',
  },
  {
    slot: 3,
    location: 'La Grande River, Chisasibi, Québec',
    photographer: 'GRAHAMUK',
    license: 'CC BY-SA 4.0',
    licenseUrl: 'https://creativecommons.org/licenses/by-sa/4.0/',
    sourceUrl:
      'https://commons.wikimedia.org/wiki/File:Aurora_borealis_glowing_over_La_Grande_River,_Chisasibi,_Quebec,_Canada_(16).jpg',
  },
  {
    slot: 4,
    location: 'Hillesøy, Tromsø, Norway',
    photographer: 'Frank Olsen',
    license: 'CC BY-SA 3.0',
    licenseUrl: 'https://creativecommons.org/licenses/by-sa/3.0/',
    sourceUrl: 'https://commons.wikimedia.org/wiki/File:Aurora_%26_sunset_A.jpg',
  },
  {
    slot: 5,
    location: 'Brofjorden, Lysekil, Sweden',
    photographer: 'W.carter',
    license: 'CC0 · public domain',
    licenseUrl: 'https://creativecommons.org/publicdomain/zero/1.0/',
    sourceUrl:
      'https://commons.wikimedia.org/wiki/File:Green_aurora_over_north_Brofjorden,_Loddebo_3.jpg',
  },
  {
    slot: 6,
    location: 'Tromsø, Norway',
    photographer: 'Lenny K Photography',
    license: 'CC BY 2.0',
    licenseUrl: 'https://creativecommons.org/licenses/by/2.0/',
    sourceUrl:
      'https://commons.wikimedia.org/wiki/File:Aurora_Borealis_(24641937989).jpg',
  },
  {
    // Commons gives no location for this Unsplash shot; 'Northern lights'
    // is a neutral, honest placeholder until a real place is confirmed.
    slot: 7,
    location: 'Northern lights',
    photographer: 'Jonathan Bean',
    license: 'CC0 · public domain',
    licenseUrl: 'https://creativecommons.org/publicdomain/zero/1.0/',
    sourceUrl:
      'https://commons.wikimedia.org/wiki/File:Jonathan_Bean_2016-10-20_(Unsplash).jpg',
  },
];

/** Deterministic pick per browser-day so the hero doesn't flicker on route change. */
export function pickAuroraForToday(): AuroraPhoto {
  const day = new Date();
  const seed = day.getUTCFullYear() * 366 + day.getUTCMonth() * 31 + day.getUTCDate();
  return AURORA_PHOTOS[seed % AURORA_PHOTOS.length];
}

/** Fully random (for e.g. the /onboarding/done reward). */
export function pickAuroraRandom(): AuroraPhoto {
  return AURORA_PHOTOS[Math.floor(Math.random() * AURORA_PHOTOS.length)];
}
