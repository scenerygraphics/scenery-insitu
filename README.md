# scenery-insitu

![Blood Cells Example, scenery running on a CAVE with a scientist exploring a Drosophila melanogaster microscopy dataset, APR representation of Zebrafish head vasculature, Rendering multiple volumes in a single scene, Interacting with microscopy data in realtime](https://cloud.mpi-cbg.de/index.php/apps/files/?dir=/&fileid=9776737#//output.gif)

A rendering system based on the [scenery](scenery.graphics) library that performs in situ visualization of distributed numerical simulations. It is instrumented to work with simulations based on [OpenFPM](http://openfpm.mpi-cbg.de/) - an open-source framework for scalable particle- and mesh-based simulations.

The application handles rendering of both volume data (e.g. from mesh-based simulations of fluid dynamics) as well as discrete particles (e.g. from  molecular dynamics simulations).

## Volume Rendering

For the rendering of volumetric data, the application performs distributed raycasting. Most of the code relevant for volume rendering can be found in the  [DistributedVolumeRenderer](./src/test/kotlin/graphics/scenery/insitu/DistributedVolumeRenderer.kt) class. The class relies on some native C++ functions, primarily for MPI communication, implementations of which can be found in the [InVis.cpp](https://git.mpi-cbg.de/openfpm/openfpm_vcluster/blob/insitu_visualization/src/VCluster/InVis.cpp) file of the OpenFPM repository. It further relies on the custom compute shaders [VDIGenerator](./src/test/resources/graphics/scenery/insitu/VDIGenerator.comp) and [AccumulateVDI](./src/test/resources/graphics/scenery/insitu/AccumulateVDI.comp), which are dynamically combined at run time, and [PlainImageCompositor](./src/test/resources/graphics/scenery/insitu/PlainImageCompositor.comp). A couple of examples using this application for in situ visualization of volumetric data from OpenFPM simulations are provided in the OpenFPM repository: [Reaction-Diffusion example](https://git.mpi-cbg.de/openfpm/openfpm_pdata/blob/insitu_visualization/example/Grid/3_gray_scott_3d/main.cpp), [Fluid Dynamics example](https://git.mpi-cbg.de/openfpm/openfpm_pdata/blob/insitu_visualization/example/Numerics/Vortex_in_cell/main_vic_petsc_opt.cpp).

## Particle Rendering

For the visualization of discrete particle-based simulations, this application supports distributed particle-as-sphere rendering. The particle rendering is performed by the [InVisRenderer](./src/test/kotlin/graphics/scenery/insitu/InVisRenderer.kt) class. An example OpenFPM-based simulation using this application for in situ visualization of discrete particles is also provided in the OpenFPM repository: [Molecular Dynamics example](https://git.mpi-cbg.de/openfpm/openfpm_pdata/blob/insitu_visualization/example/Vector/3_molecular_dynamic_insitu/main.cpp).
