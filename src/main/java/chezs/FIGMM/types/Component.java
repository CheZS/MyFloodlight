package chezs.FIGMM.types;

import Jama.Matrix;

public class Component {

	public Matrix u;
	public double sp;
	public int v;
	public double p;
	public double CDet;
	public Matrix A;
	
	public double pxjPj;
	
	public Component() {};
	public Component(Matrix u, Matrix A, double CDet) {
		this.u = u;
		this.sp = 1;
		this.v = 1;
		this.A = A;
		this.CDet = CDet;
	}
}
