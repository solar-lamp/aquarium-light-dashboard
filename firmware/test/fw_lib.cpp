// The firmware, compiled unchanged, plus C entry points for the test runner.
#include "mock/sim.h"
#include "../Aquarium_Light/Aquarium_Light.ino"

extern "C" void fw_setup() { setup(); }
extern "C" void fw_loop() { loop(); }
