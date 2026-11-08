// SPDX-License-Identifier: GPL-2.0
/*
 * String functions for ARCH=um SUBARCH=arm64 built against bionic.
 *
 * arch/arm64/um/asm/string.h declares __HAVE_ARCH_{MEMCPY,MEMSET,MEMMOVE,
 * MEMCMP,MEMCHR,STRCMP,STRCPY,STRCHR,STRCHRNUL,STRLEN,STRNCMP,STRNLEN} when
 * __ANDROID__ is defined, so lib/string.c stops compiling its own copies and
 * the UML binary resolves them from libc.a instead. That is right for the
 * binary -- it is an ordinary userspace process -- and wrong for everything
 * else that links against the kernel's symbols: an out-of-tree module has no
 * libc, so modpost reports every one of them undefined and the module cannot
 * load.
 *
 * Define weak fallbacks here and export them. When libc.a does pull its own
 * copy into the link (the reason the header above exists) the strong
 * definition wins and kernel and modules alike call libc; when it does not,
 * these are used. Either way the symbol is present and exported, which is what
 * loading a module needs.
 *
 * Only __ANDROID__ builds have anything to compile here: elsewhere
 * lib/string.c exports the real ones and a second export would be a duplicate.
 */
#ifdef __ANDROID__

#include <linux/export.h>
#include <linux/string.h>
#include <linux/compiler.h>

/* CONFIG_FORTIFY_SOURCE turns these names into macros over the builtins. */
#undef memcpy
#undef memset
#undef memmove
#undef memcmp
#undef memchr
#undef strcmp
#undef strcpy
#undef strchr
#undef strchrnul
#undef strlen
#undef strncmp
#undef strnlen

__weak void *memcpy(void *dst, const void *src, size_t n)
{
	char *d = dst;
	const char *s = src;

	while (n--)
		*d++ = *s++;
	return dst;
}
EXPORT_SYMBOL(memcpy);

__weak void *memmove(void *dst, const void *src, size_t n)
{
	unsigned char *d = dst;
	const unsigned char *s = src;

	if (d == s || !n)
		return dst;
	if (d < s) {
		while (n--)
			*d++ = *s++;
	} else {
		d += n;
		s += n;
		while (n--)
			*--d = *--s;
	}
	return dst;
}
EXPORT_SYMBOL(memmove);

__weak void *memset(void *dst, int c, size_t n)
{
	unsigned char *d = dst;

	while (n--)
		*d++ = (unsigned char)c;
	return dst;
}
EXPORT_SYMBOL(memset);

__weak int memcmp(const void *cs, const void *ct, size_t n)
{
	const unsigned char *a = cs, *b = ct;

	for (; n--; a++, b++) {
		if (*a != *b)
			return *a - *b;
	}
	return 0;
}
EXPORT_SYMBOL(memcmp);

__weak void *memchr(const void *s, int c, size_t n)
{
	const unsigned char *p = s;

	for (; n--; p++) {
		if (*p == (unsigned char)c)
			return (void *)p;
	}
	return NULL;
}
EXPORT_SYMBOL(memchr);

__weak int strcmp(const char *cs, const char *ct)
{
	unsigned char c1, c2;

	do {
		c1 = *cs++;
		c2 = *ct++;
		if (c1 != c2)
			return c1 - c2;
	} while (c1);
	return 0;
}
EXPORT_SYMBOL(strcmp);

__weak int strncmp(const char *cs, const char *ct, size_t n)
{
	unsigned char c1, c2;

	for (; n--; cs++, ct++) {
		c1 = *cs;
		c2 = *ct;
		if (c1 != c2)
			return c1 - c2;
		if (!c1)
			return 0;
	}
	return 0;
}
EXPORT_SYMBOL(strncmp);

__weak char *strcpy(char *dest, const char *src)
{
	char *d = dest;

	while ((*d++ = *src++))
		;
	return dest;
}
EXPORT_SYMBOL(strcpy);

__weak char *strchr(const char *s, int c)
{
	char ch = (char)c;

	for (;; s++) {
		if (*s == ch)
			return (char *)s;
		if (!*s)
			return NULL;
	}
}
EXPORT_SYMBOL(strchr);

__weak char *strchrnul(const char *s, int c)
{
	char ch = (char)c;

	while (*s && *s != ch)
		s++;
	return (char *)s;
}
EXPORT_SYMBOL(strchrnul);

__weak size_t strlen(const char *s)
{
	const char *sc = s;

	while (*sc)
		sc++;
	return sc - s;
}
EXPORT_SYMBOL(strlen);

__weak size_t strnlen(const char *s, size_t count)
{
	const char *sc = s;

	while (count-- && *sc)
		sc++;
	return sc - s;
}
EXPORT_SYMBOL(strnlen);

#endif /* __ANDROID__ */
