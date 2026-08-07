# Aurora hero photos — attribution

These landscape photos are shown as the hero background across the app.
All are Wikimedia Commons files, downloaded once and bundled with the SPA
— no runtime dependency on any external service.

The credit bubble in the bottom-right of each hero links back to the
Wikimedia source page, satisfying the CC attribution requirement.

If you swap any of these, remember to update
`packages/dashboard/frontend/src/lib/aurora-photos.ts` — that's the single
source of truth the UI reads.

| Slot | Location                                     | Photographer          | License          | Source                                                                                                                                          |
|------|----------------------------------------------|-----------------------|------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| 1    | Lyngenfjorden, Norway                        | Simo Räsänen          | CC BY-SA 3.0     | https://commons.wikimedia.org/wiki/File:Aurora_borealis_above_Lyngenfjorden,_2012_March.jpg                                                      |
| 2    | Iceland                                      | Pixael (Giuseppe Milo)| CC BY 2.0        | https://commons.wikimedia.org/wiki/File:The_Northern_Lights_Iceland_Travel_Photography_(209663839).jpeg                                          |
| 3    | La Grande River, Chisasibi, Québec, Canada   | GRAHAMUK              | CC BY-SA 4.0     | https://commons.wikimedia.org/wiki/File:Aurora_borealis_glowing_over_La_Grande_River,_Chisasibi,_Quebec,_Canada_(16).jpg                         |
| 4    | Hillesøy, Tromsø, Norway                     | Frank Olsen           | CC BY-SA 3.0     | https://commons.wikimedia.org/wiki/File:Aurora_%26_sunset_A.jpg                                                                                  |
| 5    | Brofjorden, Lysekil, Sweden                  | W.carter              | CC0 · public dom | https://commons.wikimedia.org/wiki/File:Green_aurora_over_north_Brofjorden,_Loddebo_3.jpg                                                        |
| 6    | Tromsø, Norway                               | Lenny K Photography   | CC BY 2.0        | https://commons.wikimedia.org/wiki/File:Aurora_Borealis_(24641937989).jpg                                                                       |
| 7    | Northern lights (location not given)         | Jonathan Bean         | CC0 · public dom | https://commons.wikimedia.org/wiki/File:Jonathan_Bean_2016-10-20_(Unsplash).jpg                                                                  |

## Why Wikimedia Commons and not Unsplash?

The original picks were from Unsplash's `landscape&license=free` filter, but
Unsplash's CDN sits behind a bot wall that blocks unattended `curl`/build
downloads — the first fetch attempt returned five identical HTML challenge
pages. Wikimedia Commons has no bot wall, comparable image quality, and
proper CC attribution baked into the metadata, which the credit bubble
component wires up automatically. Same result, less fighting infrastructure.

## Total bundle cost

~1.2 MB across the 5 JPGs at 1920px wide, q~80. Loaded eagerly on the
welcome and done screens only — every other view stays photo-free.
