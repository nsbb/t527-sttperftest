#include "kiss_fft.h"
#include <limits.h>

struct kiss_fft_state {
    int nfft;
    int inverse;
    int factors[32];
    kiss_fft_cpx twiddles[1];
};

static void kf_bfly2(kiss_fft_cpx * Fout, const size_t fstride, const kiss_fft_cfg st, int m) {
    kiss_fft_cpx * Fout2;
    kiss_fft_cpx * tw1 = st->twiddles;
    kiss_fft_cpx t;
    Fout2 = Fout + m;
    do {
        t.r = Fout2->r * tw1->r - Fout2->i * tw1->i;
        t.i = Fout2->r * tw1->i + Fout2->i * tw1->r;
        tw1 += fstride;
        Fout2->r = Fout->r - t.r;
        Fout2->i = Fout->i - t.i;
        Fout->r += t.r;
        Fout->i += t.i;
        ++Fout2;
        ++Fout;
    } while (--m);
}

static void kf_bfly4(kiss_fft_cpx * Fout, const size_t fstride, const kiss_fft_cfg st, const size_t m) {
    kiss_fft_cpx *tw1, *tw2, *tw3;
    kiss_fft_cpx scratch[6];
    size_t k = m;
    const size_t m2 = 2 * m;
    const size_t m3 = 3 * m;

    tw3 = tw2 = tw1 = st->twiddles;

    do {
        scratch[0].r = Fout[m].r * tw1->r - Fout[m].i * tw1->i;
        scratch[0].i = Fout[m].r * tw1->i + Fout[m].i * tw1->r;
        scratch[1].r = Fout[m2].r * tw2->r - Fout[m2].i * tw2->i;
        scratch[1].i = Fout[m2].r * tw2->i + Fout[m2].i * tw2->r;
        scratch[2].r = Fout[m3].r * tw3->r - Fout[m3].i * tw3->i;
        scratch[2].i = Fout[m3].r * tw3->i + Fout[m3].i * tw3->r;

        scratch[5].r = Fout->r - scratch[1].r;
        scratch[5].i = Fout->i - scratch[1].i;
        Fout->r += scratch[1].r;
        Fout->i += scratch[1].i;
        scratch[3].r = scratch[0].r + scratch[2].r;
        scratch[3].i = scratch[0].i + scratch[2].i;
        scratch[4].r = scratch[0].r - scratch[2].r;
        scratch[4].i = scratch[0].i - scratch[2].i;
        Fout[m2].r = Fout->r - scratch[3].r;
        Fout[m2].i = Fout->i - scratch[3].i;
        tw1 += fstride;
        tw2 += fstride * 2;
        tw3 += fstride * 3;
        Fout->r += scratch[3].r;
        Fout->i += scratch[3].i;

        if (st->inverse) {
            Fout[m].r = scratch[5].r - scratch[4].i;
            Fout[m].i = scratch[5].i + scratch[4].r;
            Fout[m3].r = scratch[5].r + scratch[4].i;
            Fout[m3].i = scratch[5].i - scratch[4].r;
        } else {
            Fout[m].r = scratch[5].r + scratch[4].i;
            Fout[m].i = scratch[5].i - scratch[4].r;
            Fout[m3].r = scratch[5].r - scratch[4].i;
            Fout[m3].i = scratch[5].i + scratch[4].r;
        }
        ++Fout;
    } while (--k);
}

static void kf_work(kiss_fft_cpx * Fout, const kiss_fft_cpx * f, const size_t fstride, int in_stride, int * factors, const kiss_fft_cfg st) {
    kiss_fft_cpx * Fout_beg = Fout;
    const int p = *factors++;
    const int m = *factors++;
    const kiss_fft_cpx * Fout_end = Fout + p * m;

    if (m == 1) {
        do {
            *Fout = *f;
            f += fstride * in_stride;
        } while (++Fout != Fout_end);
    } else {
        do {
            kf_work(Fout, f, fstride * p, in_stride, factors, st);
            f += fstride * in_stride;
        } while ((Fout += m) != Fout_end);
    }

    Fout = Fout_beg;

    switch (p) {
        case 2: kf_bfly2(Fout, fstride, st, m); break;
        case 4: kf_bfly4(Fout, fstride, st, m); break;
        default:
            for (int i = 0; i < p; i++) {
                for (int j = 0; j < m; j++) {
                    Fout[i * m + j] = Fout_beg[j * p + i];
                }
            }
            break;
    }
}

static void kf_factor(int n, int * facbuf) {
    int p = 4;
    double floor_sqrt = floor(sqrt((double)n));

    do {
        while (n % p) {
            switch (p) {
                case 4: p = 2; break;
                case 2: p = 3; break;
                default: p += 2; break;
            }
            if (p > floor_sqrt)
                p = n;
        }
        n /= p;
        *facbuf++ = p;
        *facbuf++ = n;
    } while (n > 1);
}

kiss_fft_cfg kiss_fft_alloc(int nfft, int inverse_fft, void * mem, size_t * lenmem) {
    kiss_fft_cfg st = NULL;
    size_t memneeded = sizeof(struct kiss_fft_state) + sizeof(kiss_fft_cpx) * (nfft - 1);

    if (lenmem == NULL) {
        st = (kiss_fft_cfg)KISS_FFT_MALLOC(memneeded);
    } else {
        if (mem != NULL && *lenmem >= memneeded)
            st = (kiss_fft_cfg)mem;
        *lenmem = memneeded;
    }
    if (st) {
        int i;
        st->nfft = nfft;
        st->inverse = inverse_fft;

        for (i = 0; i < nfft; ++i) {
            const double pi = 3.141592653589793238462643383279502884197169399375105820974944;
            double phase = -2 * pi * i / nfft;
            if (st->inverse)
                phase *= -1;
            st->twiddles[i].r = cos(phase);
            st->twiddles[i].i = sin(phase);
        }

        kf_factor(nfft, st->factors);
    }
    return st;
}

void kiss_fft(kiss_fft_cfg st, const kiss_fft_cpx *fin, kiss_fft_cpx *fout) {
    kf_work(fout, fin, 1, 1, st->factors, st);
}

void kiss_fft_cleanup(void) {
}

void kiss_fft_free(kiss_fft_cfg cfg) {
    if (cfg) {
        KISS_FFT_FREE(cfg);
    }
}
