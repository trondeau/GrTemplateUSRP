// Required
#include <jni.h>

// We'll likely want these
#include <vector>
#include <string>

// Get any GNU Radio headers
#include <gnuradio/top_block.h>
#include <gnuradio/uhd/usrp_source.h>
#include <gnuradio/blocks/complex_to_real.h>
#include <gnuradio/blocks/multiply_const_ff.h>
#include <grand/opensl_sink.h>

// Declare the global virtual machine and top-block objects
JavaVM *vm;
gr::top_block_sptr tb;

extern "C" {

JNIEXPORT void JNICALL
Java_org_gnuradio_grtemplateusrp_MainActivity_FgInit(JNIEnv* env,
                                                     jobject thiz,
                                                     int fd, jstring devname)
{
  GR_INFO("fg", "FgInit Called");

  const char *usbfs_path = env->GetStringUTFChars(devname, NULL);
  std::stringstream args;
  args << "uhd,fd=" << fd << ",usbfs_path=" << usbfs_path;
  GR_INFO("fg", boost::str(boost::format("Using UHD args=%1%") % args.str()));

  uhd::stream_args_t stream_args;
  stream_args.cpu_format = "fc32";
  stream_args.otw_format = "sc16";

  float samp_rate = 48e3;  // 48 kHz

  // Declare our GNU Radio blocks
  gr::uhd::usrp_source::sptr src;
  gr::blocks::complex_to_real::sptr c2r;
  gr::blocks::multiply_const_ff::sptr mult;
  gr::grand::opensl_sink::sptr snk;

  // Construct the objects for every block in the flowgraph
  tb = gr::make_top_block("fg");
  src = gr::uhd::usrp_source::make(args.str(), stream_args);
  c2r = gr::blocks::complex_to_real::make();
  mult = gr::blocks::multiply_const_ff::make(0.0);
  snk = gr::grand::opensl_sink::make(int(samp_rate));

  src->set_samp_rate(200e3);
  src->set_center_freq(101.1e6);
  src->set_gain(20); // adjust as needed

  // Connect up the flowgraph
  tb->connect(src, 0, c2r, 0);
  tb->connect(c2r, 0, mult, 0);
  tb->connect(mult, 0, snk, 0);
}

JNIEXPORT void JNICALL
Java_org_gnuradio_grtemplateusrp_MainActivity_FgStart(JNIEnv* env,
                                                      jobject thiz)
{
  GR_INFO("fg", "FgStart Called");
  tb->start();
}

JNIEXPORT void JNICALL
Java_org_gnuradio_grtemplateusrp_MainActivity_FgStop(JNIEnv* env,
                                                     jobject thiz)
{
  GR_INFO("fg", "FgStop Called");
  tb->stop();
  tb->wait();
  GR_INFO("fg", "FgStop Exited");
}

JNIEXPORT jstring JNICALL
Java_org_gnuradio_grtemplateusrp_MainActivity_FgRep(JNIEnv* env,
                                                    jobject thiz)
{
  return env->NewStringUTF(tb->edge_list().c_str());
}

}
