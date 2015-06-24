package com.alex.example.fixlicense.actions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWorkbenchWindowActionDelegate;

import com.alex.example.fixlicense.Activator;

/**
 * 
 * @author Alex Chen
 *
 */
public class SampleAction implements IWorkbenchWindowActionDelegate {
	private IWorkbenchWindow window;
	
	private final String LICENSE_FILE_NAME = "license.txt";
	private final String LICENSE_INLINE_FILE_NAME = "license_inline.txt";
	private String license;
	private String license_inline;
	
	private int sum;

	public SampleAction() {
	}

	public void run(IAction action) {
		license = getLicenseContent(LICENSE_FILE_NAME);
		license_inline = getLicenseContent(LICENSE_INLINE_FILE_NAME);
		if (license_inline.endsWith("\n")) {
			license_inline = license_inline.substring(0, license_inline.length() - 1);
		}
		sum = 0;
		
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		IWorkspaceRoot root = workspace.getRoot();
		IProject[] projects = root.getProjects();
		for (IProject project : projects) {
			try {
				if (project.isOpen()) {
					processProject(project);
				}		
			} catch (Exception e) {
				MessageDialog.openInformation(window.getShell(), "Fix License", "Exception happened, please check the console log.");
				e.printStackTrace();
				return;
			}
		}
		MessageDialog.openInformation(window.getShell(), "Fix License", "All java source files have been processed. Total = " + sum);
	}
	
	private void processProject(IProject project) throws Exception {
		if (project.isNatureEnabled("org.eclipse.jdt.core.javanature")) {
			IJavaProject javaProject = JavaCore.create(project);
			IPackageFragment[] packages = javaProject.getPackageFragments();
			for (IPackageFragment mypackage : packages) {
				if (mypackage.getKind() == IPackageFragmentRoot.K_SOURCE) {
					for (ICompilationUnit unit : mypackage.getCompilationUnits()) {
						sum = sum + 1;
						processJavaSource(unit);
					}
				}
			}
		}
	}

	private void processJavaSource(ICompilationUnit unit) throws Exception {
		ITextFileBufferManager bufferManager = FileBuffers.getTextFileBufferManager();
		IPath path = unit.getPath();
		try {
			bufferManager.connect(path, null);
			ITextFileBuffer textFileBuffer = bufferManager.getTextFileBuffer(path);
			IDocument doc = textFileBuffer.getDocument();
			if ((license !=null) && (license.length() > 0)) {
				processHeadLicense(doc);
			}
			if ((license_inline != null) && (license_inline.length() > 0)) {
				processInlineLicense(doc);
			}
			textFileBuffer.commit(null, false);
		} finally {
			bufferManager.disconnect(path, null);
		}
	}
	
	private CompilationUnit getAST(IDocument doc) {
		ASTParser parser = ASTParser.newParser(AST.JLS4);
	    parser.setKind(ASTParser.K_COMPILATION_UNIT);
	    parser.setSource(doc.get().toCharArray());
	    parser.setResolveBindings(true);
	    CompilationUnit cu = (CompilationUnit) parser.createAST(null);
	    
	    return cu;
	}
	
	private void processHeadLicense(IDocument doc) throws Exception {
		CompilationUnit cu = getAST(doc);
		Comment comment = null;
		if (cu.getCommentList().size() == 0) {
			doc.replace(0, 0, license);
		} else {
			comment = (Comment)cu.getCommentList().get(0);
			String firstComment = doc.get().substring(comment.getStartPosition(), comment.getStartPosition() + comment.getLength());
			if (validateHeadLicense(firstComment)) {
				doc.replace(comment.getStartPosition(), comment.getLength(), license);
			} else {
				doc.replace(0, 0, license);
			}		
		}
	}

	private void processInlineLicense(IDocument doc) throws Exception {
		CompilationUnit cu = getAST(doc);
	    cu.recordModifications();
	    AST ast = cu.getAST();

	    if (cu.types().get(0) instanceof TypeDeclaration) {
	    	TypeDeclaration td = (TypeDeclaration)cu.types().get(0);
	    	FieldDeclaration[] fd = td.getFields();
	    	if (fd.length == 0) {
	    		td.bodyDeclarations().add(0, createLiceseInLineField(ast));
	    	} else {
	    		FieldDeclaration firstFd = fd[0];
	    		VariableDeclarationFragment vdf = (VariableDeclarationFragment)firstFd.fragments().get(0);
	    		if (vdf.getName().getIdentifier().equals("COPYRIGHT")) {
	    			td.bodyDeclarations().remove(0);
	    			td.bodyDeclarations().add(0, createLiceseInLineField(ast));
	    		} else {
	    			td.bodyDeclarations().add(0, createLiceseInLineField(ast));
	    		}
	    	}	    	
	    }
	    
		//record changes
		TextEdit edits = cu.rewrite(doc, null);
		edits.apply(doc);
	}
	
	private FieldDeclaration createLiceseInLineField(AST ast) {
		VariableDeclarationFragment vdf = ast.newVariableDeclarationFragment();
		vdf.setName(ast.newSimpleName("COPYRIGHT"));
		StringLiteral sl = ast.newStringLiteral();
		sl.setLiteralValue(license_inline);
		vdf.setInitializer(sl);
		FieldDeclaration fd = ast.newFieldDeclaration(vdf);
		fd.modifiers().addAll(ast.newModifiers(Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL));
		fd.setType(ast.newSimpleType(ast.newSimpleName("String")));
		
		return fd;
	}
	
	private boolean validateHeadLicense(String license) {
		if (license.contains("IBM Confidential") && license.contains("Copyright IBM Corporation")) {
			return true;
		}
		
		return false;
	}
	
	private String getLicenseContent(String licenseFileName) {
		URL url = Activator.getDefault().getBundle().getResource(licenseFileName);
		try {
			StringBuffer sb = new StringBuffer();
			BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()));
			String str = br.readLine();
			while (str != null) {
				sb.append(str);
				sb.append("\n");
				str = br.readLine();
			}			
			return sb.toString();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return null;
	}
	
	public void selectionChanged(IAction action, ISelection selection) {
	}

	public void dispose() {
	}

	public void init(IWorkbenchWindow window) {
		this.window = window;
	}	
}
