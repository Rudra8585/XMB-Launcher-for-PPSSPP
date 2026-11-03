#pragma once
#include <stdlib.h>
inline void *AllocateAlignedMemory(size_t size, size_t alignment) {
	void *p = nullptr;
	if (posix_memalign(&p, alignment, size) != 0) return nullptr;
	return p;
}
inline void FreeAlignedMemory(void *p) { free(p); }
