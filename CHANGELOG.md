# Changelog

## [0.3.0](https://github.com/Elijah-Dangerfield/Cards/compare/v0.2.0...v0.3.0) (2026-09-05)


### Features

* **build:** make obfuscated crash reports readable in Sentry ([3e4f2ca](https://github.com/Elijah-Dangerfield/Cards/commit/3e4f2ca882f31ecb61c9524098aa23e1e79ad0c7))
* **build:** turn on R8, and regenerate the baseline profile monthly ([abd133c](https://github.com/Elijah-Dangerfield/Cards/commit/abd133c6104a548b4c4605867243742026cd2280))
* **debug:** surface StrictMode violations in the app, and drop dead screenshot wiring ([9b66d67](https://github.com/Elijah-Dangerfield/Cards/commit/9b66d675e8515ddcd4541f5b9e3fc549db796669))
* **home:** tell players when a newer version is on the store ([43d5e38](https://github.com/Elijah-Dangerfield/Cards/commit/43d5e38d468fc96cec9312bea0093c89f272a3d1))
* **perf:** generate a Baseline Profile from a real user journey ([76d1f02](https://github.com/Elijah-Dangerfield/Cards/commit/76d1f027bd433a290c88f91c02392b19156d6f17))
* **perf:** let the profile generator skip onboarding, and reach the table ([0ece309](https://github.com/Elijah-Dangerfield/Cards/commit/0ece3094416ad82e1c04c9727f356359b4e5ee6e))
* **telemetry:** measure cold-start time and chart it on dc-perf ([b67ade0](https://github.com/Elijah-Dangerfield/Cards/commit/b67ade02b0898484e76f9d85afbaabc3674f1b3c))
* **telemetry:** report real-user jank per screen, and turn on StrictMode ([f8c9895](https://github.com/Elijah-Dangerfield/Cards/commit/f8c9895db2115748ce93908718fbddcadd49b07f))


### Bug Fixes

* **benchmark:** stop the benchmark hook from blocking app launch ([cb521a4](https://github.com/Elijah-Dangerfield/Cards/commit/cb521a4d1d5c243fda073b00d2bb963445a4345e))
* **build:** make the benchmark failure dump actually work ([63517d7](https://github.com/Elijah-Dangerfield/Cards/commit/63517d79a8d947d6febf36eb673efecc621cb51c))
* **build:** put the benchmark failure dump on one line ([85817e3](https://github.com/Elijah-Dangerfield/Cards/commit/85817e323d3f35f53fd7f4d72239167caad023fe))
* **build:** unblock R8, and keep crash output readable ([ce5c993](https://github.com/Elijah-Dangerfield/Cards/commit/ce5c993fe52cdf326d4e6e3e4071f1b6d853ce5e))
* **build:** wait for the action bar, not a Fold button that does not exist ([cb107f7](https://github.com/Elijah-Dangerfield/Cards/commit/cb107f794eb1c750b8af85eaaf6358692a8704f5))
* **navigation:** stop canOpenURL blocking every outbound link on iOS ([101d2a1](https://github.com/Elijah-Dangerfield/Cards/commit/101d2a1f20104279e16f61287053dd6008a160c4))
* **navigation:** stop canOpenURL blocking every outbound link on iOS ([ca77c1a](https://github.com/Elijah-Dangerfield/Cards/commit/ca77c1af475da53881347b8132a7ec19564db6d1))
* **room:** revert the hole-card change, fix the opponent ring instead ([c0e813e](https://github.com/Elijah-Dangerfield/Cards/commit/c0e813ec4a4e08a2a6a6a2df7d02cbc058b992c3))
* **room:** stop a null board card from crashing the play screen ([31afdcc](https://github.com/Elijah-Dangerfield/Cards/commit/31afdcc7ec8b095974731df359fa1b000e074c3d))
* **room:** tap-to-flip stopped working after the first hand ([82e57c4](https://github.com/Elijah-Dangerfield/Cards/commit/82e57c4fad592328619883f29a2a7cfe4142a47d))
* **server:** give each player action its own trace ([ac58b1b](https://github.com/Elijah-Dangerfield/Cards/commit/ac58b1ba54b0ce15a010cb8636f45a30a2c3df49))
* **server:** put equipment sync in a rate-limit bucket that fits its cadence ([550f6c5](https://github.com/Elijah-Dangerfield/Cards/commit/550f6c5d018686ac743e27291747d538a7bc2a9d))
* **ui:** make the animated-state detekt rule run, and clear what it found ([0dcbe48](https://github.com/Elijah-Dangerfield/Cards/commit/0dcbe48d2d2430f39beb47cb93dd4732bc5d0ba1))
* **ui:** stop previews animating forever, behind one shared primitive ([479484f](https://github.com/Elijah-Dangerfield/Cards/commit/479484fc24ab9a2c4a41a5cee4b059bc76593fcc))


### Performance Improvements

* **build:** split the startup profile from the journey profile ([d2d01fe](https://github.com/Elijah-Dangerfield/Cards/commit/d2d01fef77b7ba605b87dd12d724c8f756ca61eb))
* performance sweep, R8, baseline profiles and startup telemetry ([caabef8](https://github.com/Elijah-Dangerfield/Cards/commit/caabef8715d788a05ba03dbb188173b7cfd794bd))
* **room:** defer the hole-card flip reads out of composition too ([054d987](https://github.com/Elijah-Dangerfield/Cards/commit/054d98776e35b6ec64289021461c0af75e4b9f95))
* **room:** finish the ENG-49 sweep — four more composition-scope reads ([d5bc323](https://github.com/Elijah-Dangerfield/Cards/commit/d5bc323c67804bacb040808f69b7675e697e0076))
* **room:** measure Compose skippability instead of assuming it ([4f35611](https://github.com/Elijah-Dangerfield/Cards/commit/4f356112edf938fa30953c7528f6524f002b3026))
* **room:** move the play screen's card animations off the composition phase ([a5a634a](https://github.com/Elijah-Dangerfield/Cards/commit/a5a634a26c4acdc7303c0533577a807d5012d6d4))
* **room:** stop the turn pulse recomposing the whole player area (ENG-49) ([68cfb53](https://github.com/Elijah-Dangerfield/Cards/commit/68cfb53bbb3b9a9197642c505ea429c7bc3b2710))
* **server:** checkpoint room snapshots at hand boundaries ([6213485](https://github.com/Elijah-Dangerfield/Cards/commit/62134850826290f675791e748ed27d80840d7556))
* **server:** checkpoint snapshots at hand boundaries and fix gameplay tracing ([0bf60f3](https://github.com/Elijah-Dangerfield/Cards/commit/0bf60f387a6cd71c21baba1865665e470945d2ad))
* **server:** checkpoint snapshots at hand boundaries and fix the gameplay trace/dashboard blind spots ([d614dd2](https://github.com/Elijah-Dangerfield/Cards/commit/d614dd2988735460cf1b085886a8ee46bd57afa8))
* **ui:** draw the XP rings from the draw phase, not composition ([7b034a0](https://github.com/Elijah-Dangerfield/Cards/commit/7b034a0ff5e714943a9b470bdbf549321d55608f))
* **ui:** quantize rolling numbers so they stop rebuilding text per frame ([2629c52](https://github.com/Elijah-Dangerfield/Cards/commit/2629c52324ac6de57524bf3ff40444473139595d))


### Reverts

* **ui:** put previews back on the JetBrains annotation ([50506c1](https://github.com/Elijah-Dangerfield/Cards/commit/50506c160390b8ec2c099fb3ef9f20f6747ed46d))

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
