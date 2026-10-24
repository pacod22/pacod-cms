# The space demo's photographs

The pictures of the space demo ("Starward", `demo/demo-space.json`) are real photographs and
artist's impressions from NASA, captioned in the demo as the invented places of the Reach. Each
caption carries the credit below, and the seed records each picture's NASA id and source address
(`nasaId`, `source`), which `SeedIntegrityTest` holds to this folder: every file here is used, every
picture is here.

## What was taken, and what was not

Only NASA imagery, from the NASA Image and Video Library (images.nasa.gov):

- photographs and artist's concepts credited to NASA or a NASA centre, or released by JPL
  (Photojournal, `PIA…`) and credited NASA/JPL or NASA/JPL-Caltech with or without the NASA
  mission's instrument team (Space Science Institute, MSSS, the University of Arizona's HiRISE
  team, Texas A&M, the New Horizons team at Johns Hopkins APL and SwRI). NASA's media guidelines
  hold NASA material generally not subject to copyright in the United States; JPL's image use
  policy lets its releases be used for any purpose with credit;
- photographs taken from orbit only by NASA astronauts, named in the credit.

Left out, whatever the picture: anything credited to another space agency (ESA, CSA, JAXA, ASI,
DLR, CNES), a company or a private person; anything whose metadata says "courtesy", "©" or
"copyright restrictions apply" (one NASA Ames painting did, and none of its series is used);
pictures of people, insignia, flags, logos or lettering; recognisable spacecraft; recognisable
places on Earth. NASA endorses nothing here, and no NASA identifier appears.

## How the files were made

Each original was downloaded from images.nasa.gov, cropped to 3:2 -- the shape of a website's
default image rule (600x400 minimum) -- where a ringed planet's rings would have been cut, padded
with black instead -- scaled down to at most 1500x1000 (halving with bilinear filtering, then one
bicubic step, a light sharpening), and saved as a baseline JPEG of at most about 330 KB with no
metadata. At install time `DemoPhotos` fits each one to the website's own rule and byte limit.

To add or replace one: keep to the rules above, crop to 3:2 at no more than 1500 wide, add the
picture to the seed with its `photo`, `credit`, `nasaId` and `source`, and a row here.

## The pictures

| File | NASA id | What it really shows | Credit |
|---|---|---|---|
| `aster-oceans.jpg` | [PIA19824](https://images.nasa.gov/details/PIA19824) | Soaking up the Rays of a Sun-like Star Artist Concept | NASA/Ames/JPL-Caltech |
| `calder-aurora-green.jpg` | [iss029e007502](https://images.nasa.gov/details/iss029e007502) | "Aurora Australis, Airglow, Terminator  view taken by the Expedition 29 crew" | NASA (Mike Fossum) |
| `calder-aurora-south.jpg` | [iss059e092459](https://images.nasa.gov/details/iss059e092459) | Earth observation taken by Expedition 59 crew | NASA (Christina Koch) |
| `calder-milky-way.jpg` | [iss072e574663](https://images.nasa.gov/details/iss072e574663) | Earth Observations | NASA (Don Pettit) |
| `calder-red-dawn.jpg` | [iss036e028913](https://images.nasa.gov/details/iss036e028913) | sunset | NASA (Karen Nyberg) |
| `kesh-furnace.jpg` | [PIA14888](https://images.nasa.gov/details/PIA14888) | Kepler-20e -- The Smallest Exoplanet Artist Concept | NASA/Ames/JPL-Caltech |
| `lantern-butterfly.jpg` | [PIA23121](https://images.nasa.gov/details/PIA23121) | Space Butterfly | NASA/JPL-Caltech (infrared) |
| `lantern-nursery.jpg` | [PIA23865](https://images.nasa.gov/details/PIA23865) | Spitzer Image of Star Factory W51 | NASA/JPL-Caltech (infrared) |
| `lantern-reef.jpg` | [PIA13845](https://images.nasa.gov/details/PIA13845) | North America Nebula in Different Lights | NASA/JPL-Caltech (infrared) |
| `lantern-twin-fires.jpg` | [PIA14106](https://images.nasa.gov/details/PIA14106) | Making a Spectacle of Star Formation in Orion | NASA/JPL-Caltech (infrared) |
| `lantern-veil.jpg` | [PIA18905](https://images.nasa.gov/details/PIA18905) | Horsehead of a Different Color | NASA/JPL-Caltech (infrared) |
| `lantern-wing.jpg` | [PIA23126](https://images.nasa.gov/details/PIA23126) | Cepheus C and Cepheus B Region by Spitzer (Two-Instrument) | NASA/JPL-Caltech (infrared) |
| `lantern-witch.jpg` | [PIA17553](https://images.nasa.gov/details/PIA17553) | Witch Head Brews Baby Stars | NASA/JPL-Caltech (infrared) |
| `mirren-halo.jpg` | [PIA21590](https://images.nasa.gov/details/PIA21590) | Blue Rays: New Horizons High-Res Farewell to Pluto | NASA/Johns Hopkins University Applied Physics Laboratory/Southwest Research Institute |
| `mirren-haze.jpg` | [PIA19947](https://images.nasa.gov/details/PIA19947) | Closer Look: Majestic Mountains and Frozen Plains | NASA/Johns Hopkins University Applied Physics Laboratory/Southwest Research Institute |
| `mirren-mountains.jpg` | [PIA19948](https://images.nasa.gov/details/PIA19948) | Pluto Majestic Mountains, Frozen Plains and Foggy Hazes | NASA/Johns Hopkins University Applied Physics Laboratory/Southwest Research Institute |
| `orrin-blue-sunset.jpg` | [PIA19401](https://images.nasa.gov/details/PIA19401) | Sunset Sequence in Mars Gale Crater Animation | NASA/JPL-Caltech/MSSS/Texas A&M Univ |
| `orrin-dune-crescent.jpg` | [PIA24036](https://images.nasa.gov/details/PIA24036) | HiRISE Finds a Dune and Ripples | NASA/JPL-Caltech/University of Arizona |
| `orrin-dune-sea.jpg` | [PIA19941](https://images.nasa.gov/details/PIA19941) | Which Came First? | NASA/JPL-Caltech/Univ. of Arizona |
| `orrin-dunes-blue.jpg` | [PIA24461](https://images.nasa.gov/details/PIA24461) | Bright and Dark Dunes | NASA/JPL-Caltech/University of Arizona |
| `orrin-dunes-night.jpg` | [PIA25351](https://images.nasa.gov/details/PIA25351) | Dunes in Meridiani Planum | NASA/JPL-Caltech/University of Arizona |
| `orrin-gediz-rim.jpg` | [PIA19803](https://images.nasa.gov/details/PIA19803) | Curiosity Rover View of Alluring Martian Geology Ahead | NASA/JPL-Caltech/MSSS |
| `orrin-mount.jpg` | [PIA23898](https://images.nasa.gov/details/PIA23898) | A Dramatic View of Mars' Mount Sharp | NASA/JPL-Caltech/MSSS |
| `orrin-pass.jpg` | [PIA25414](https://images.nasa.gov/details/PIA25414) | Curiosity's View of Sand Ridges and Bolívar | NASA/JPL-Caltech/MSSS |
| `selka-boulder.jpg` | [jsc2004e52779](https://images.nasa.gov/details/jsc2004e52779) | An Apollo 17 panorama of the lunar surface and its split boulder, cropped to leave out the astronaut and the rover | NASA |
| `selka-hills.jpg` | [jsc2011e118361](https://images.nasa.gov/details/jsc2011e118361) | An Apollo 15 panorama of the lunar surface, cropped to leave out the astronaut, the rover and the lunar module | NASA |
| `selka-rising.jpg` | [iss028e020073](https://images.nasa.gov/details/iss028e020073) | Lunar Observation | NASA (Ron Garan) |
| `tessaly-afterglow.jpg` | [PIA14934](https://images.nasa.gov/details/PIA14934) | A Splendor Seldom Seen | NASA/JPL-Caltech/Space Science Institute |
| `tessaly-eclipse.jpg` | [PIA17172](https://images.nasa.gov/details/PIA17172) | The Day the Earth Smiled | NASA/JPL-Caltech/Space Science Institute |
| `tessaly-equinox.jpg` | [PIA11141](https://images.nasa.gov/details/PIA11141) | Saturn ... Four Years On | NASA/JPL/Space Science Institute |
| `tessaly-portrait.jpg` | [PIA06193](https://images.nasa.gov/details/PIA06193) | The Greatest Saturn Portrait ...Yet | NASA/JPL/Space Science Institute |
| `tessaly-rose.jpg` | [PIA14944](https://images.nasa.gov/details/PIA14944) | The Rose | NASA/JPL-Caltech/Space Science Institute |
| `tessaly-shadow.jpg` | [PIA21345](https://images.nasa.gov/details/PIA21345) | So Far from Home | NASA/JPL-Caltech/Space Science Institute |
| `thule-transit.jpg` | [PIA14922](https://images.nasa.gov/details/PIA14922) | Colorful Colossuses and Changing Hues | NASA/JPL-Caltech/Space Science Institute |
| `veyra-fractures.jpg` | [PIA08355](https://images.nasa.gov/details/PIA08355) | Exploring Icy Canyons | NASA/JPL/Space Science Institute |
| `veyra-glass-shore.jpg` | [PIA21423](https://images.nasa.gov/details/PIA21423) | Surface of TRAPPIST-1f | NASA/JPL-Caltech |
| `veyra-plumes.jpg` | [PIA11688](https://images.nasa.gov/details/PIA11688) | Bursting at the Seams: the Geyser Basin of Enceladus | NASA/JPL/Space Science Institute |
