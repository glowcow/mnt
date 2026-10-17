/* mnt: the most one decoder's dictionary may take, set by the app from the phone's free memory; a larger one is refused and noted. */
#ifndef MNT_LIMIT_H
#define MNT_LIMIT_H

extern unsigned long long mnt_memory_limit;
extern _Thread_local int mnt_memory_refused;

#endif
