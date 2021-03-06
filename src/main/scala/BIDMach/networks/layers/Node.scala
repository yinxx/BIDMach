package BIDMach.networks.layers

import BIDMat.{Mat,SBMat,CMat,DMat,FMat,IMat,LMat,HMat,GMat,GDMat,GIMat,GLMat,GSMat,GSDMat,SMat,SDMat}
import BIDMat.MatFunctions._
import BIDMat.SciFunctions._
import BIDMach.datasources._
import BIDMach.updaters._
import BIDMach.mixins._
import BIDMach.models._
import BIDMach.networks.layers._
import BIDMach._
import edu.berkeley.bid.CPUMACH
import edu.berkeley.bid.CUMACH
import jcuda.jcudnn._
import jcuda.jcudnn.JCudnn._
import scala.util.hashing.MurmurHash3;
import scala.language.implicitConversions
import java.util.HashMap;
import BIDMach.networks._


@SerialVersionUID(100L)
trait NodeOpts extends BIDMat.Opts {
  var name = "";  
  
  def copyOpts(opts:NodeOpts):NodeOpts = {
    opts.name = name;
		opts;
  }
}

class Node extends NodeTerm(null, 0) with NodeOpts {
	val inputs:Array[NodeTerm] = Array(null);
  var myLayer:Layer = null;
  var myGhost:Node = null;
  var parent:Node = null;
  var outputNumbers:Array[Int] = null;
  
  override def node = this;
  
  def copyTo(opts:Node):Node = {
    copyOpts(opts);
    opts.inputs(0) = inputs(0);
    myGhost = opts;
    opts;
  }
  
  override def toString = {
    "node@"+(hashCode % 0x10000).toString
  }

  override def clone:Node = {
		copyTo(new Node).asInstanceOf[Node];
  } 
  
  def apply(i:Int) = new NodeTerm(this, i);
  
  def create(net:Net):Layer = {null}
}


class NodeTerm(val _node:Node, val term:Int) extends Serializable {  
  
  def node = _node;
  
  def +    (a:NodeTerm) = {val n=this; new AddNode{inputs(0)=n; inputs(1)=a}};
  
  def -    (a:NodeTerm) = {val n=this; new SubNode{inputs(0)=n; inputs(1)=a}};

  def *@   (a:NodeTerm) = {val n=this; new MulNode{inputs(0)=n; inputs(1)=a;}};
    
  def ∘    (a:NodeTerm) = {val n=this; new MulNode{inputs(0)=n; inputs(1)=a;}};
  
  def dot  (a:NodeTerm) = {val n=this; new DotNode{inputs(0)=n; inputs(1)=a;}};
  
  def ∙    (a:NodeTerm) = {val n=this; new DotNode{inputs(0)=n; inputs(1)=a;}};
        
  def over (a:NodeTerm) = {val n=this; new StackNode{inputs(0)=n; inputs(1)=a;}};
  
  def apply(a:NodeTerm) = {val n=this; new SelectNode{inputs(0)=n; inputs(1)=a;}};
}

object Node {
  
  def batchNorm(a:NodeTerm)(avgFactor:Float=0.1f, normMode:Int=BatchNormLayer.SPATIAL) = {
    new BatchNormNode{inputs(0)=a; expAvgFactor=avgFactor; batchNormMode=normMode}    
  }
  
  def batchNormScale(a:NodeTerm)(name:String="", avgFactor:Float=0.1f, normMode:Int=BatchNormLayer.SPATIAL, hasBias:Boolean = true) = {
  	val hb = hasBias;
  	val mname = name;
    new BatchNormScaleNode{inputs(0)=a; modelName=mname; expAvgFactor=avgFactor; batchNormMode=normMode; hasBias=hb}    
  }
    
  def constant(v:Mat) = {
    new ConstantNode{value = v;}
  }
    
  def conv(a:NodeTerm)(name:String="", w:Int, h:Int, nch:Int, initv:Float = 1f, stride:IMat = irow(1), pad:IMat = irow(1), 
      hasBias:Boolean = true, convType:Int=cudnnConvolutionMode.CUDNN_CROSS_CORRELATION) = {
    val str = stride;
    val pd = pad;
    val hb = hasBias;
    val initv0 = initv;
    val mname = name;
    val ct = convType;
    new ConvNode{inputs(0)=a; modelName=mname; kernel=irow(w,h); noutputs=nch; initv = initv0; stride=str; pad=pd; hasBias=hb; convType=ct}
  }
  
  def copy(a:NodeTerm) = new CopyNode{inputs(0) = a;}

  def copy() = new CopyNode;
  
  def crop(a:NodeTerm)(sizes:IMat=irow(3,224,224,0), offsets:IMat=irow(0,-1,-1,-1)) = {
    val csizes = sizes;
    val coffsets = offsets;
    new CropNode{inputs(0) = a; sizes = csizes; offsets = coffsets};
  }
  
  def dropout(a:NodeTerm)(frac:Float=0.5f) = new DropoutNode{inputs(0) = a; frac = frac}
  
  def efn(a:NodeTerm)(fwdfn:(Float)=>Float=null, bwdfn:(Float,Float,Float)=>Float=null) = {
    val fwd = fwdfn;
    val bwd = bwdfn;
    new EfnNode{inputs(0) = a; fwdfn=fwd; bwdfn=bwd};
  }
  
  def exp(a:NodeTerm) = new ExpNode{inputs(0) = a;};
  
  def forward(a:NodeTerm) = new ForwardNode{inputs(0) = a;}
  
  def fn(a:NodeTerm)(fwdfn:(Mat)=>Mat=null, bwdfn:(Mat,Mat,Mat)=>Mat=null) = {
    val fwd = fwdfn;
    val bwd = bwdfn;
    new FnNode{inputs(0) = a; fwdfn=fwd; bwdfn=bwd};
  }
  
  def glm_(a:NodeTerm)(implicit opts:GLMNodeOpts) = new GLMNode{inputs(0) = a; links = opts.links};
  
  def glm(a:NodeTerm)(links:IMat) = {
    val ilinks = links; 
    new GLMNode{inputs(0) = a; links = ilinks}
    };
  
  def input(a:NodeTerm) = new InputNode{inputs(0) = a;};
  
  def input = new InputNode;
  
  def format(a:NodeTerm)(conversion:Int = TensorFormatLayer.AUTO, inputFormat:Int = Net.TensorNHWC) = {
    val con = conversion;
    val fmt = inputFormat;
    new TensorFormatNode{inputs(0) = a; conversion = con; inputFormat = fmt;}
  }
  
  def linear(a:NodeTerm)(name:String="", outdim:Int=0, hasBias:Boolean=true, aopts:ADAGrad.Opts=null, initv:Float=1f,
      withInteractions:Boolean=false, tmatShape:(Int,Int)=>(Array[Int], Array[Int], Array[Int], Array[Int]) = null) = {
    val odim = outdim;
    val hBias = hasBias;
    val aaopts = aopts;
    val mname = name;
    val wi = withInteractions;
    val tms = tmatShape; 
    val initv0 = initv;
    new LinNode{inputs(0)=a; modelName = mname; outdim=odim; hasBias=hBias; initv=initv0; aopts=aaopts; withInteractions = wi; tmatShape = tms};
  }
  
  def linear_(a:NodeTerm)(implicit opts:LinNodeOpts) = {
    val n = new LinNode{inputs(0) = a;}
    opts.copyOpts(n);
    n
  }
    
  def ln(a:NodeTerm) = new LnNode{inputs(0) = a};
  
  def lstm(h:NodeTerm, c:NodeTerm, i:NodeTerm, m:String)(opts:LSTMNodeOpts) = {
    val n = new LSTMNode;
    opts.copyOpts(n);
    n.modelName = m;
    n.constructGraph;
    n.inputs(0) = h;
    n.inputs(1) = c;
    n.inputs(2) = i;
    n
  }
  
  def lstm_fused(inc:NodeTerm, lin1:NodeTerm, lin2:NodeTerm, lin3:NodeTerm, lin4:NodeTerm) = {
    new LSTMfusedNode{
      inputs(0) = inc;
      inputs(1) = lin1;
      inputs(2) = lin2;
      inputs(3) = lin3;
      inputs(4) = lin4;
    }
  }
  
  def negsamp(a:NodeTerm)(name:String="", outdim:Int=0, hasBias:Boolean=true, aopts:ADAGrad.Opts=null, nsamps:Int=100, expt:Float=0.5f, scoreType:Int=0, doCorrect:Boolean=true) = {
    val odim = outdim;
    val hBias = hasBias;
    val aaopts = aopts;
    val nnsamps = nsamps;
    val eexpt = expt;
    val dcr = doCorrect;
    val sct = scoreType;
    val mname = name;
    new NegsampOutputNode{inputs(0)=a; modelName=mname; outdim=odim; hasBias=hBias; aopts=aaopts; nsamps=nnsamps; expt=eexpt; scoreType=sct; docorrect=dcr};
  }
    
  def negsamp_(a:NodeTerm)(implicit opts:NegsampOutputNodeOpts) =     {
    val n = new NegsampOutputNode{inputs(0) = a}
    opts.copyOpts(n);
    n
  }
  
  def norm(a:NodeTerm)(targetNorm:Float = 1f, weight:Float = 1f) =  {
    val tnorm = targetNorm;
    val nweight = weight;
    new NormNode{inputs(0) = a; targetNorm = tnorm; weight = nweight}
  }
  
  def norm_(a:NodeTerm)(implicit opts:NormNodeOpts) =  {
    val n = new NormNode{inputs(0) = a;}
    opts.copyOpts(n);
    n
  }
  
  def oneHot(a:NodeTerm) = new OnehotNode{inputs(0) = a};
  
  def pool(a:NodeTerm)(h:Int=1, w:Int=1, stride:Int=1, pad:Int=0, 
      poolingMode:Int=cudnnPoolingMode.CUDNN_POOLING_MAX, 
      poolingNaN:Int = cudnnNanPropagation.CUDNN_PROPAGATE_NAN,
      tensorFormat:Int = Net.UseNetFormat) = {
  	val hh = h;
  	val ww = w;
  	val str = stride;
  	val ppad = pad;
  	val pm = poolingMode;
  	val pn = poolingNaN;
  	val tf = tensorFormat;
    new PoolingNode{inputs(0)=a; h=hh; w=ww; stride=str; pad=ppad; poolingMode=pm; poolingNaN=pn; tensorFormat=tf;}; 
  }
  
  def rect(a:NodeTerm) = new RectNode{inputs(0) = a};
  
  def relu(a:NodeTerm) = new RectNode{inputs(0) = a};
  
  def scale(a:NodeTerm)(name:String="", normMode:Int=BatchNormLayer.SPATIAL, hasBias:Boolean = true) = {
  	val hb = hasBias;
  	val mname = name;
    new ScaleNode{inputs(0)=a; modelName=mname; batchNormMode=normMode; hasBias=hb}    
  }
  
  def sigmoid(a:NodeTerm) = new SigmoidNode{inputs(0) = a};
  
  def σ(a:NodeTerm) = new SigmoidNode{inputs(0) = a};

  def softmax(a:NodeTerm) = new SoftmaxNode{inputs(0) = a};
  
  def softmaxout(a:NodeTerm)(scoreType:Int=0, doVar:Boolean=false) =  {
    val scoreTyp = scoreType;
    new SoftmaxOutputNode{inputs(0) = a; scoreType=scoreTyp; doVariance = doVar}
  }
  
  def softplus(a:NodeTerm) = new SoftplusNode{inputs(0) = a};
  
  def splithoriz(a:NodeTerm)(np:Int) = new SplitHorizNode{inputs(0) = a; nparts = np};
  
  def splitvert(a:NodeTerm)(np:Int) = new SplitVertNode{inputs(0) = a; nparts = np};
  
  def sum(a:NodeTerm) = new SumNode{inputs(0) = a;}
  
  def tanh(a:NodeTerm) = new TanhNode{inputs(0) = a};
  
  implicit def NodeToNodeMat(n:Node):NodeMat = NodeMat.elem(n);

}