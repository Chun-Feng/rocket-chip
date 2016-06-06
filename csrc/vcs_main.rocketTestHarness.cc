// See LICENSE for license details.

#include "htif_emulator.h"
#include "mm.h"
#include "mm_dramsim2.h"
#include <DirectC.h>
#include <stdio.h>
#include <stdlib.h>
#include <vector>
#include <sstream>
#include <iterator>

extern "C" {

extern int vcs_main(int argc, char** argv);

static htif_emulator_t* htif;
static unsigned htif_bytes = HTIF_WIDTH / 8;
static const char* loadmem;
static bool dramsim = false;
static int memory_channel_mux_select = 0;

void htif_fini(vc_handle failure)
{
  delete htif;
  htif = NULL;
  exit(vc_getScalar(failure));
}

int main(int argc, char** argv)
{
  for (int i = 1; i < argc; i++)
  {
    if (!strcmp(argv[i], "+dramsim"))
      dramsim = true;
    else if (!strncmp(argv[i], "+memory_channel_mux_select=", 27))
      memory_channel_mux_select = atoi(argv[i]+27);
  }

  htif = new htif_emulator_t(std::vector<std::string>(argv + 1, argv + argc));

  vcs_main(argc, argv);
  abort(); // should never get here
}

void make_mm(vc_handle mm_ptr) {
  mm_t *mm = dramsim ? (mm_t*)(new mm_dramsim2_t) : (mm_t*)(new mm_magic_t);
  mm->init(MEM_SIZE / N_MEM_CHANNELS, MEM_DATA_BITS / 8, CACHE_BLOCK_BYTES);
  vc_putPointer(mm_ptr, mm);
}

void memory_tick(
  vc_handle mm_ptr,

  vc_handle ar_valid,
  vc_handle ar_ready,
  vc_handle ar_addr,
  vc_handle ar_id,
  vc_handle ar_size,
  vc_handle ar_len,

  vc_handle aw_valid,
  vc_handle aw_ready,
  vc_handle aw_addr,
  vc_handle aw_id,
  vc_handle aw_size,
  vc_handle aw_len,

  vc_handle w_valid,
  vc_handle w_ready,
  vc_handle w_strb,
  vc_handle w_data,
  vc_handle w_last,

  vc_handle r_valid,
  vc_handle r_ready,
  vc_handle r_resp,
  vc_handle r_id,
  vc_handle r_data,
  vc_handle r_last,

  vc_handle b_valid,
  vc_handle b_ready,
  vc_handle b_resp,
  vc_handle b_id)
{
  mm_t* mmc = (mm_t *) vc_getPointer(mm_ptr);

  uint32_t write_data[mmc->get_word_size()/sizeof(uint32_t)];
  for (size_t i = 0; i < mmc->get_word_size()/sizeof(uint32_t); i++)
    write_data[i] = vc_4stVectorRef(w_data)[i].d;

  uint32_t aw_id_val, ar_id_val;

  if (MEM_ID_BITS == 1) {
    aw_id_val = vc_getScalar(aw_id);
    ar_id_val = vc_getScalar(ar_id);
  } else {
    aw_id_val = vc_4stVectorRef(aw_id)->d;
    ar_id_val = vc_4stVectorRef(ar_id)->d;
  }

  mmc->tick
  (
    vc_getScalar(ar_valid),
    vc_4stVectorRef(ar_addr)->d - MEM_BASE,
    ar_id_val,
    vc_4stVectorRef(ar_size)->d,
    vc_4stVectorRef(ar_len)->d,

    vc_getScalar(aw_valid),
    vc_4stVectorRef(aw_addr)->d - MEM_BASE,
    aw_id_val,
    vc_4stVectorRef(aw_size)->d,
    vc_4stVectorRef(aw_len)->d,

    vc_getScalar(w_valid),
    vc_4stVectorRef(w_strb)->d,
    write_data,
    vc_getScalar(w_last),

    vc_getScalar(r_ready),
    vc_getScalar(b_ready)
  );

  vc_putScalar(ar_ready, mmc->ar_ready());
  vc_putScalar(aw_ready, mmc->aw_ready());
  vc_putScalar(w_ready, mmc->w_ready());
  vc_putScalar(b_valid, mmc->b_valid());
  vc_putScalar(r_valid, mmc->r_valid());
  vc_putScalar(r_last, mmc->r_last());

  vec32 d[mmc->get_word_size()/sizeof(uint32_t)];

  d[0].c = 0;
  d[0].d = mmc->b_resp();
  vc_put4stVector(b_resp, d);

  d[0].c = 0;
  d[0].d = mmc->r_resp();
  vc_put4stVector(r_resp, d);

  if (MEM_ID_BITS > 1) {
    d[0].c = 0;
    d[0].d = mmc->b_id();
    vc_put4stVector(b_id, d);

    d[0].c = 0;
    d[0].d = mmc->r_id();
    vc_put4stVector(r_id, d);
  } else {
    vc_putScalar(b_id, mmc->b_id());
    vc_putScalar(r_id, mmc->r_id());
  }

  for (size_t i = 0; i < mmc->get_word_size()/sizeof(uint32_t); i++)
  {
    d[i].c = 0;
    d[i].d = ((uint32_t*)mmc->r_data())[i];
  }
  vc_put4stVector(r_data, d);
}

void htif_tick
(
  vc_handle htif_in_valid,
  vc_handle htif_in_ready,
  vc_handle htif_in_bits,
  vc_handle htif_out_valid,
  vc_handle htif_out_ready,
  vc_handle htif_out_bits,
  vc_handle exit
)
{
  static bool peek_in_valid;
  static uint32_t peek_in_bits;
  if (vc_getScalar(htif_in_ready))
    peek_in_valid = htif->recv_nonblocking(&peek_in_bits, htif_bytes);

  vc_putScalar(htif_out_ready, 1);
  if (vc_getScalar(htif_out_valid))
  {
    vec32* bits = vc_4stVectorRef(htif_out_bits);
    htif->send(&bits->d, htif_bytes);
  }

  vec32 bits = {0, 0};
  bits.d = peek_in_bits;
  vc_put4stVector(htif_in_bits, &bits);
  vc_putScalar(htif_in_valid, peek_in_valid);

  bits.d = htif->done() ? (htif->exit_code() << 1 | 1) : 0;
  vc_put4stVector(exit, &bits);
}

}
