# scenery-insitu

A rendering system based on the [scenery](scenery.graphics) library that performs in situ visualization of parallel numerical simulations. It is instrumented to work with simulations based on [OpenFPM](http://openfpm.mpi-cbg.de/) - an open-source framework for scalable particle- and mesh-based simulations. The application runs distributedly, reads simulation data from shared memory, and renders then composits it into a single image, all with zero-copy of simulation data.

This repository is under development.

For the shared memory communication, we have developed a library that transparently handles movement of data. This can be found at [/src/test/cpp](./src/test/cpp).

A prototype has been developed that performs in situ particle-as-sphere rendering and subsequent compositing. The prototype can be found [here](./src/test/kotlin/graphics/scenery/insitu/SharedSpheresExample.kt).
