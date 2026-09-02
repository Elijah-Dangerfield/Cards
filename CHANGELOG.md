# Changelog

## [0.2.0](https://github.com/Elijah-Dangerfield/Cards/compare/v0.1.0...v0.2.0) (2026-09-02)


### Features

* **config:** add founding-member welcome window config + welcomeSeen flag ([b2c6a83](https://github.com/Elijah-Dangerfield/Cards/commit/b2c6a833361f7c097cc0fb8ca27fdd905a451c90))
* founding-member welcome dialog + accumulated develop work ([b88fbc0](https://github.com/Elijah-Dangerfield/Cards/commit/b88fbc078e7e5c7cb7d88e2f237182d75378b90f))
* **home:** open the store listing for reviews instead of the in-app prompt ([461ad43](https://github.com/Elijah-Dangerfield/Cards/commit/461ad43ad9060969ae9ab546a95801feffe2f54b))
* **home:** rework the welcome dialog into a founding-member thank-you ([4dba0db](https://github.com/Elijah-Dangerfield/Cards/commit/4dba0dbf4b30de489c2f5a9e7299b6dedbb73612))
* instrument the starter-grant reveal so a missed reveal is not silent ([1ac61c5](https://github.com/Elijah-Dangerfield/Cards/commit/1ac61c5dada5bcaf80327ba0eb430646aa03f28a))
* **networking:** stop a blocked client from talking to the server (ENG-35) ([e16139f](https://github.com/Elijah-Dangerfield/Cards/commit/e16139f6ad4e58bd458fde9eb52d9820695af9dd))
* **onboarding:** enlarge the Downcard wordmark on the welcome step ([3f6dfb5](https://github.com/Elijah-Dangerfield/Cards/commit/3f6dfb5e772ff71fa8d5baeb74b8c3b8ab127396))
* **server:** stamp signup platform on new profiles (ENG-39) ([78a0494](https://github.com/Elijah-Dangerfield/Cards/commit/78a049481436d56130988936d9146ed9dd3c9b93))
* **shop:** make a store that sells nothing impossible to miss (ENG-43) ([8a2360d](https://github.com/Elijah-Dangerfield/Cards/commit/8a2360da16a03068e5fb53171b17b8bcc219539b))
* signup platform, install facts, and a quieter client ([#143](https://github.com/Elijah-Dangerfield/Cards/issues/143)) ([3779e91](https://github.com/Elijah-Dangerfield/Cards/commit/3779e91900ab8849831e2473cfe45d692066301d))
* **telemetry:** per-run iOS foreground-termination signal (ENG-42) ([d5377f4](https://github.com/Elijah-Dangerfield/Cards/commit/d5377f4afd7626215a868b6e7d3b5af5aacd7fd9))
* **telemetry:** stamp install/device facts on the OTel Resource (ENG-38) ([932ad54](https://github.com/Elijah-Dangerfield/Cards/commit/932ad547abe143706214356f8541e1d9d21b2c6b))


### Bug Fixes

* **auth:** answer a stranded session with a typed 401, not a raw 500 (AUTH-29) ([2cfef3c](https://github.com/Elijah-Dangerfield/Cards/commit/2cfef3cf7ee24605de713e38cb8c0f6f0850a967))
* **auth:** don't destroy a guest session on an unreachable-backend boot (AUTH-30) ([#142](https://github.com/Elijah-Dangerfield/Cards/issues/142)) ([fdf208e](https://github.com/Elijah-Dangerfield/Cards/commit/fdf208e801aa3a8684908957d3d7d219bfcfa084))
* **auth:** only blame the caller's own account for a user FK violation ([329f8dd](https://github.com/Elijah-Dangerfield/Cards/commit/329f8dd3bab00c1d6b71019601618e452a1c3ea1))
* **networking:** a request we refused to send is not a backend outage ([22903ff](https://github.com/Elijah-Dangerfield/Cards/commit/22903ffce6cfd2d96638eade90a6080781f10e94))
* **onboarding:** close the two ways an abandonment marker outlives its run ([90fccf4](https://github.com/Elijah-Dangerfield/Cards/commit/90fccf47bec3bc036a5b293c373b8a228fbbac92))
* **onboarding:** count an abandonment when the user doesn't come back (AUTH-31) ([419629d](https://github.com/Elijah-Dangerfield/Cards/commit/419629dd0ec73cfd9795da5f4f0b71656ce37158))
* **progression:** batch the XP sync write, page the flush (ENG-45) ([c86cb4e](https://github.com/Elijah-Dangerfield/Cards/commit/c86cb4e41a7e6c7267b15619644c521d0a0c184d))
* **progression:** make the XP total update relative and stop the drain hiding failures (ENG-45) ([bb9afca](https://github.com/Elijah-Dangerfield/Cards/commit/bb9afcae8fb27dce6c5fbe0b3ba3d6fe189baaaf))
* **room:** one Rebuy tap sends one rebuy (MP-38) ([c17caa8](https://github.com/Elijah-Dangerfield/Cards/commit/c17caa8226819272c77f5f434f3dc9b3c15d83ef))
* seed onboarding.starterGrant so the reveal shows a number ([307af6e](https://github.com/Elijah-Dangerfield/Cards/commit/307af6e51e86c9a32c1546e45d987abe46109a2d))
* **server:** don't strand mid-hand joiners when an advance is refused ([731dc76](https://github.com/Elijah-Dangerfield/Cards/commit/731dc76df83d69cf10599f3eb400bc8c3612b9e3))
* **server:** let join hydrate a persisted room after a restart ([7cb4645](https://github.com/Elijah-Dangerfield/Cards/commit/7cb464525a0537044cf74d94727db0a28163df97))
* **server:** log and throttle failed admin-token attempts (ENG-41) ([ec0755f](https://github.com/Elijah-Dangerfield/Cards/commit/ec0755f81abf84a527e49842de21b31818805cf8))
* **server:** room join hydration and stranded mid-hand joiners ([29dbf20](https://github.com/Elijah-Dangerfield/Cards/commit/29dbf20fa7f6b1a69bfaf403a4c454cc90ed6650))
* **telemetry:** one classifier decides what counts as an error (ENG-44) ([44f62fa](https://github.com/Elijah-Dangerfield/Cards/commit/44f62fa32d5f3365cbf5847ae1b9ea1bb7879069))
* **telemetry:** stop two install-facts heuristics from deleting real users ([ff18d94](https://github.com/Elijah-Dangerfield/Cards/commit/ff18d943e4ed525eaf949c32857a206e3bcaafa3))
* **test:** pass AdminConfig to installRateLimits in the MP harness ([6674509](https://github.com/Elijah-Dangerfield/Cards/commit/6674509f106839e8a5f90e3ba7a5ecc6e9bc2634))
* **ui:** don't count the initial composition as a recomposition (ENG-42) ([f7b67e1](https://github.com/Elijah-Dangerfield/Cards/commit/f7b67e11825e05cd76444ce0bbb4b8ca326942c1))


### Performance Improvements

* **sync:** batch the play-style and player-stats writes (ENG-47) ([0fa3030](https://github.com/Elijah-Dangerfield/Cards/commit/0fa3030030bc5828e0aa0482c02fa7fb0a894a7f))
* **sync:** batch the play-style and player-stats writes (ENG-47) ([8759af4](https://github.com/Elijah-Dangerfield/Cards/commit/8759af4e81c71992a513be0c4f78ac5c4ab2e7d0))

## 0.1.0 (2026-07-23)


### Miscellaneous Chores

* reset release line to 0.1.0 for first public release ([da153b1](https://github.com/Elijah-Dangerfield/Cards/commit/da153b1e89c6a30c63da4e0b16bfbe78e20efef3))

## Changelog
