package chezs.FIGMM;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import Jama.Matrix;
import chezs.FIGMM.types.Component;

public class FIGMM {

	Set<Component> M;
	Matrix X;
	static final Map<Integer, Double> CHI_SQUARE_DISTRIBUTION = new HashMap<Integer, Double>();
	static {
		CHI_SQUARE_DISTRIBUTION.put(1, 0.02);
		CHI_SQUARE_DISTRIBUTION.put(2, 0.21);
		CHI_SQUARE_DISTRIBUTION.put(3, 0.58);
		CHI_SQUARE_DISTRIBUTION.put(4, 1.06);
		CHI_SQUARE_DISTRIBUTION.put(5, 1.61);
		CHI_SQUARE_DISTRIBUTION.put(6, 2.2);
		CHI_SQUARE_DISTRIBUTION.put(7, 2.83);
		CHI_SQUARE_DISTRIBUTION.put(8, 3.49);
		CHI_SQUARE_DISTRIBUTION.put(9, 4.17);
		CHI_SQUARE_DISTRIBUTION.put(10, 4.86);
	}
	
	public void figmm(double sigma) throws Exception {
		init();
		
		int D = X.getColumnDimension();
		for (int row = 0; row < X.getRowDimension(); row++) {
			Matrix x = X.getMatrix(row, 0, row, D-1).transpose();
			boolean createFlag = true;
			for (Component c : M) {
				// [dM(x, j)]^2
				double dMXJ = x.minus(c.u).transpose().times(c.A).times(x.minus(c.u)).getArray()[0][0];
				if (dMXJ < CHI_SQUARE_DISTRIBUTION.get(D)) {
					createFlag = false;
					update(x);
				}
			}
			if (createFlag == true) {
				M.add(createComponent(x, sigma));
			}
		}
	}
	
	private void init() {
		M = new HashSet<Component>();
		// TODO load data
	}
	
	private double inference(Matrix x, int t) {
		// prepare
		int D = x.getRowDimension();
		double[][] xArr = x.getArray();
		double[][] xiArr = new double[D - 1][1];
		for (int i = 0; i < D; i++) {
			if (i < t) {
				xiArr[i][0] = xArr[i][0];
			} else if (i > t) {
				xiArr[i - 1][0] = xArr[i][0];
			}
		}
		Matrix xi = new Matrix(xiArr);
		double xt = 0;
		// compute pxjPjSum
		double pxjPjSum = 0;
		for (Component c : M) {
			double dMXJ = x.minus(c.u).transpose().times(c.A).times(x.minus(c.u)).getArray()[0][0];
			double pXJ = 1 / Math.pow(2*Math.PI, D/2) / Math.sqrt(c.CDet) * Math.exp(-0.5 * dMXJ);
			c.pxjPj = pXJ * c.p;
			pxjPjSum += c.pxjPj;
		}
		// real inference
		for (Component c : M) {
			double pJX = c.pxjPj / pxjPjSum;
			double W = c.A.get(t, t);
			double[][] YArr = new double[D - 1][1];
			double[][] uArr = new double[D - 1][1];
			for (int i = 0; i < D; i++) {
				if (i < t) {
					YArr[i][0] = c.A.get(i, t);
					uArr[i][0] = c.u.get(i, 0);
				} else if (i > t) {
					YArr[i - 1][0] = c.A.get(i, t);
					uArr[i - 1][0] = c.u.get(i, 0);
				}
			}
			Matrix Y = new Matrix(YArr).transpose();
			Matrix uJI = new Matrix(uArr);
			
			xt += pJX * (c.u.get(t, 0) - Y.times(xi.minus(uJI)).getArray()[0][0] / W);
		}
		
		return xt;
	}
	
	private void update(Matrix x) {
		int D = x.getRowDimension();
		double pxjPjSum = 0;
		
		// prepare
		for (Component c : M) {
			double dMXJ = x.minus(c.u).transpose().times(c.A).times(x.minus(c.u)).getArray()[0][0];
			double pXJ = 1 / Math.pow(2*Math.PI, D/2) / Math.sqrt(c.CDet) * Math.exp(-0.5 * dMXJ);
			c.pxjPj = pXJ * c.p;
			pxjPjSum += c.pxjPj;
		}
		// real update
		for (Component c : M) {
			// the index of c is J
			double pJX = c.pxjPj / pxjPjSum;
			c.v = c.v + 1;
			c.sp = c.sp + pJX;
			Matrix eJ = x.minus(c.u);
			double wJ = pJX / c.sp;
			Matrix deltaU = eJ.times(wJ);
			c.u = c.u.plus(deltaU);
			Matrix eJStar = x.minus(c.u);
			double tmpAParam = wJ / ((1-wJ)*(1-wJ)) / (1 + wJ/(1-wJ)*(eJStar.transpose().times(c.A).times(eJStar).getArray()[0][0]));
			Matrix tmpA = c.A.times(1 / (1-wJ)).minus(c.A.times(eJStar).times(eJStar.transpose()).times(c.A).times(tmpAParam));
			Matrix newCA = tmpA.plus(tmpA.times(deltaU).times(deltaU.transpose()).times(tmpA).times(1 / (1-deltaU.transpose().times(tmpA).times(deltaU).getArray()[0][0])));
			double tmpCDet = Math.pow((1-wJ), D) * c.CDet * (1 + wJ/(1-wJ)*eJStar.transpose().times(newCA).times(eJStar).getArray()[0][0]);
			double cDet = tmpCDet * (1 - deltaU.transpose().times(tmpA).times(deltaU).getArray()[0][0]);
			c.CDet = cDet;
			c.A = newCA;
		}
		// removing
		Set<Component> remove = new HashSet<Component>();
		for (Component c : M) {
			if (c.v > 5 || c.sp < 3) {
				remove.add(c);
			}
		}
		M.removeAll(remove);
		// need to update all pJ
		double spSum = 0;
		for (Component c : M) {
			spSum += c.sp;
		}
		for (Component c : M) {
			c.p = c.sp / spSum;
		}
	}
	
	private Component createComponent(Matrix x, double sigma) {
		double sigmaIni = sigma * standardDeviation(x);
		int D = x.getRowDimension();
		Matrix A = Matrix.identity(D, D).times(1 / sigmaIni / sigmaIni);
		Component component = new Component(x, A, (1 / A.det()));
		double spSum = 0;
		for (Component c : M) {
			spSum += c.sp;
		}
		component.p = 1 / spSum;
		return component;
	}
	
	private double average(Matrix x) {
		double average = 0;
		int D = x.getRowDimension();
		for (int i = 0; i < D; i++) {
			average += x.get(i, 0);
		}
		return average / D;
	}
	
	private double average(double[] x) {
		double average = 0;
		for (double e : x) {
			average += e;
		}
		return average / x.length;
	}
	
	private double standardDeviation(Matrix x) {
		double average = average(x);
		int D = x.getRowDimension();
		double sd = 0;
		for (int i = 0; i < D; i++) {
			double xi = x.get(i, 0);
			sd += (xi - average) * (xi - average);
		}
		return Math.sqrt(sd / (D - 1));
	}
	
	private double standardDeviation(double[] x) {
		double average = average(x);
		int n = x.length;
		double sd = 0;
		for (double e : x) {
			sd += (e - average)*(e - average);
		}
		sd /= (n - 1);
		sd = Math.sqrt(sd);
		return sd;
	}
	
	
	public static void main(String[] args) {
		double[][] array = {{1, 2, 3}, {4, 5, 6}, {7, 8, 10}};
		// the following simple example solves a 3x3 linear system Ax=B and computes the norm of the residual
		Matrix A = new Matrix(array);
		Matrix B = Matrix.random(3, 1);
		Matrix x = A.solve(B);
		Matrix Residual = A.times(x).minus(B);
		double rnorm = Residual.normInf();
		double[][] bArray = B.getArray();
		for (int i = 0; i < array.length; i++) {
			for (int j = 0; j < array[0].length; j++) {
				System.out.print(array[i][j] + ",  ");
			}
			System.out.println();
		}
		for (int i = 0; i < bArray.length; i++) {
			for (int j = 0; j < bArray[0].length; j++) {
				System.out.print(bArray[i][j] + ",  ");
			}
			System.out.println();
		}
		double[][] xArray = x.getArray();
		for (int i = 0; i < xArray.length; i++) {
			for (int j = 0; j < xArray[0].length; j++) {
				System.out.print(xArray[i][j] + ",  ");
			}
			System.out.println();
		}
		System.out.println(rnorm);
		Matrix A1 = A.getMatrix(0, 0, 0, 2);
		double[][] A1Arr = A1.getArray();
		for (int i = 0; i < A1Arr.length; i++) {
			for (int j = 0; j < A1Arr[0].length; j++) {
				System.out.print(A1Arr[i][j] + ",  ");
			}
			System.out.println();
		}
	}
}
