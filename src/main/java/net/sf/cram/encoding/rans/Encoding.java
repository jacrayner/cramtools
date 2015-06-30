package net.sf.cram.encoding.rans;

import java.nio.ByteBuffer;

class Encoding {

	static class RansEncSymbol {
		int x_max; // (Exclusive) upper bound of pre-normalization interval
		int rcp_freq; // Fixed-point reciprocal frequency
		int bias; // Bias
		int cmpl_freq; // Complement of frequency: (1 << scale_bits) - freq
		int rcp_shift; // Reciprocal shift
	}

	static void RansEncSymbolInit(RansEncSymbol s, int start, int freq, int scale_bits) {
		// RansAssert(scale_bits <= 16); RansAssert(start <= (1u <<
		// scale_bits)); RansAssert(freq <= (1u << scale_bits) - start);

		s.x_max = ((Constants.RANS_BYTE_L >> scale_bits) << 8) * freq;
		s.cmpl_freq = (1 << scale_bits) - freq;
		if (freq < 2) {
			s.rcp_freq = (int) ~0L;
			s.rcp_shift = 0;
			s.bias = start + (1 << scale_bits) - 1;
		} else {
			// Alverson, "Integer Division using reciprocals"
			// shift=ceil(log2(freq))
			int shift = 0;
			while (freq > (1L << shift))
				shift++;

			s.rcp_freq = (int) (((1L << (shift + 31)) + freq - 1) / freq);
			s.rcp_shift = shift - 1;

			// With these values, 'q' is the correct quotient, so we
			// have bias=start.
			s.bias = start;
		}

		s.rcp_shift += 32; // Avoid the extra >>32 in RansEncPutSymbol
	}

	static int RansEncPutSymbol(int r, ByteBuffer ptr, RansEncSymbol sym) {
		assert (sym.x_max != 0); // can't encode symbol with freq=0

		// renormalize
		int x = r;
		int x_max = sym.x_max;
		// System.out.printf("x=%d, x_max=%d\n", x, x_max);
		if (x >= x_max) {
			if (x >= x_max) {
				ptr.put((byte) (x & 0xFF));
				// System.out.printf("ptr put at %d: %d %d %d\n",
				// ptr.position() - 1, (x & 0xFF), ((byte) (x & 0xFF)),
				// ptr.arrayOffset());
				x >>= 8;
				if (x >= x_max) {
					ptr.put((byte) (x & 0xFF));
					// System.out.printf("ptr put at %d: %d %d %d\n",
					// ptr.position() - 1, (x & 0xFF),
					// ((byte) (x & 0xFF)), ptr.arrayOffset());
					x >>= 8;
				}
			}
		}

		// x = C(s,x)
		// NOTE: written this way so we get a 32-bit "multiply high" when
		// available. If you're on a 64-bit platform with cheap multiplies
		// (e.g. x64), just bake the +32 into rcp_shift.
		// int q = (int) (((uint64_t)x * sym.rcp_freq) >> 32) >> sym.rcp_shift;

		// The extra >>32 has already been added to RansEncSymbolInit
		long q = ((x * (0xFFFFFFFFL & sym.rcp_freq)) >> sym.rcp_shift);
		r = (int) (x + sym.bias + q * sym.cmpl_freq);
		// System.out.printf("x=%d, sym->rcp_freq=%d, sym->rcp_shift=%d\n", x,
		// sym.rcp_freq, sym.rcp_shift);
		// System.out.printf("q=%d, *r=%d\n", q, r);
		return r;
	}
}
