/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.duke.cs.osprey.dof;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;

import edu.duke.cs.osprey.control.EnvironmentVars;
import edu.duke.cs.osprey.dof.deeper.SidechainIdealizer;
import edu.duke.cs.osprey.restypes.HardCodedResidueInfo;
import edu.duke.cs.osprey.restypes.ResidueTemplate;
import edu.duke.cs.osprey.restypes.ResidueTemplateLibrary;
import edu.duke.cs.osprey.structure.Atom;
import edu.duke.cs.osprey.structure.Molecule;
import edu.duke.cs.osprey.structure.Residue;
import edu.duke.cs.osprey.tools.RigidBodyMotion;

/**
 *
 * @author mhall44
 */

// TODO: this isn't really a DOF
public class ResidueTypeDOF extends DegreeOfFreedom {
    //This degree of freedom is the residue type (e.g., AA type) at a particular position
    //So applying values of it means mutating the residue
    
    private static final long serialVersionUID = 4285811771185813789L;
    
    // TODO: this should be final and not transient, but we're stuck using weird serialization for now
    public transient ResidueTemplateLibrary templateLib;
    
    private Residue res;//what residue in the molecule we are talking about
    private boolean idealizeSidechainAfterMutation;
    
    /**
     * this doesn't need to be a DOF anymore, just the static function switchToTemplate()
     */
    @Deprecated
    public ResidueTypeDOF(ResidueTemplateLibrary templateLib, Residue res) {
        this(templateLib, res, false);
    }
    
    /**
     * this doesn't need to be a DOF anymore, just the static function switchToTemplate()
     */
    @Deprecated
    public ResidueTypeDOF(ResidueTemplateLibrary templateLib, Residue res, boolean idealizeSidechainAfterMutation) {
        this.templateLib = templateLib;
        this.res = res;
        this.idealizeSidechainAfterMutation = idealizeSidechainAfterMutation;
    }
    
    // TEMP TODO HACKHACK: Java's serialization system forces us to de-serialize object without any context
    // but these DoFs need the template library, which apparently doesn't serialize correctly
    // (it's stupid... we shouldn't need to serialize the template library as part of the EPIC matrix anyway...)
    // so explicitly force Java's default de-serializer to use the EnvironmentVars
    // for now... need to find a better way to do this in the future
    private void readObject(ObjectInputStream ois)
    throws ClassNotFoundException, IOException {
        ois.defaultReadObject();
        templateLib = EnvironmentVars.resTemplates;
        assert (templateLib != null);
    }
    
    public void mutateTo(String resType) {
        switchToTemplate(getLibraryTemplate(resType));
    }
    
    public ResidueTemplate getLibraryTemplate(String resType) {
        return templateLib.getTemplateForMutation(resType, res);
    }
    
    public boolean isTemplate(ResidueTemplate template) {
    	return this.res.template == template;
    }
    
    public void switchToTemplate(ResidueTemplate newTemplate) {
    	switchToTemplate(templateLib, res, newTemplate, idealizeSidechainAfterMutation);
    }
    
    public static void switchToTemplate(ResidueTemplateLibrary templateLib, Residue res, ResidueTemplate newTemplate) {
    	switchToTemplate(templateLib, res, newTemplate, false);
    }
    
    public static void switchToTemplate(ResidueTemplateLibrary templateLib, Residue res, ResidueTemplate newTemplate, boolean idealizeSidechainAfterMutation) {
        ResidueTemplate oldTemplate = res.template;
        
        //the residue's going to change some, so break its inter-residue bonds
        res.removeInterResBonds();
        res.intraResBondsMarked = false;//we'll need to redo these too
        
        res.template = newTemplate;
        
        res.fullName = newTemplate.name + res.fullName.substring(3);
        //res type name is first three characters of full name
        
        
        //coordinates will come from the template,
        //but we'll move them as a rigid body to match the backbone atoms
        int[][] mutAlignAtoms = HardCodedResidueInfo.findMutAlignmentAtoms(oldTemplate,newTemplate);
        //2x4 array of which atoms are used in the old and new residues to align
        //to do the mutation
        
        double oldMAACoords[][] = extractCoords(mutAlignAtoms[0],res.coords);
        //coordinates of the atoms in the old residue to align
        
        double newCoords[] = newTemplate.templateRes.coords.clone();
        double templateMAACords[][] = extractCoords(mutAlignAtoms[1],newCoords);
        
        //we now construct a rigid-body motion that will map the sidechain (or generally,
        //the non-"backbone" part, if not a standard amino acid) from the template to the residue's
        //curent frame of reference
        RigidBodyMotion templMotion = new RigidBodyMotion(templateMAACords,oldMAACoords);
        
        templMotion.transform(newCoords);
        
        //the backbone atoms will be kept exactly as before the mutation
        //if the sidechain attaches only to the first mutAlignAtom, this method keeps bond lengths
        //exactly as in the template for sidechain, and as in the old backbone otherwise
        ArrayList<String> BBAtomNames =  HardCodedResidueInfo.listBBAtomsForMut(newTemplate,oldTemplate);
        for(String BBAtomName : BBAtomNames){
            int BBAtomIndexOld = oldTemplate.templateRes.getAtomIndexByName(BBAtomName);
            int BBAtomIndexNew = newTemplate.templateRes.getAtomIndexByName(BBAtomName);
            
            //copy coordinates of the BB atom from old to new coordinates
            System.arraycopy(res.coords, 3*BBAtomIndexOld, newCoords, 3*BBAtomIndexNew, 3);
        }
        
        res.coords = newCoords;
        
        //finally, update atoms in res to match new template
        ArrayList<Atom> newAtoms = new ArrayList<>();
        for(Atom at : newTemplate.templateRes.atoms){
            Atom newAtom = at.copy();
            newAtom.res = res;
            newAtoms.add(newAtom);
        }
        res.atoms = newAtoms;    
        
        //reconnect all bonds
        res.markIntraResBondsByTemplate();
        HardCodedResidueInfo.reconnectInterResBonds(res);
        
        //special case if sidechain loops back in additional place to backbone...
        if(oldTemplate.name.equalsIgnoreCase("PRO") || newTemplate.name.equalsIgnoreCase("PRO")){
            if (idealizeSidechainAfterMutation) {
                SidechainIdealizer.idealizeSidechain(templateLib, res);
            }
            if(!newTemplate.name.equalsIgnoreCase("PRO")){//if mutating from Pro, no ring closure issues possible anymore
                if(res.pucker!=null){
                    if(res.pucker.puckerProblem != null){
                        res.pucker.puckerProblem.removeFromRes();
                        res.pucker.puckerProblem = null;
                    }
                }
            }
        }
        else if(idealizeSidechainAfterMutation){
            SidechainIdealizer.idealizeSidechain(templateLib, res);
        }
    }
    
    public void restoreCoordsFromTemplate() {
    
        // get the alignment of backbone atoms
        int[][] mutAlignAtoms = HardCodedResidueInfo.findMutAlignmentAtoms(res.template, res.template);
        double resBBCoords[][] = extractCoords(mutAlignAtoms[0], res.coords);
        double templateBBCoords[][] = extractCoords(mutAlignAtoms[1], res.template.templateRes.coords);
        
        // rotation from template to res
        RigidBodyMotion xform = new RigidBodyMotion(templateBBCoords, resBBCoords);
        for (Atom atom : res.atoms) {
            
            // skip backbone atoms
            if (HardCodedResidueInfo.possibleBBAtomsLookup.contains(atom.name)) {
                continue;
            }
            
            // transform sidechain atoms
            int i = atom.indexInRes*3;
            System.arraycopy(res.template.templateRes.coords, i, res.coords, i, 3);
            xform.transform(res.coords, atom.indexInRes);
        }
    }
    
    
    static double[][] extractCoords(int[] index, double[] allCoords){
        //allCoords is the concatenated coords of a bunch of atoms
        //get the coords (3-D) of the atoms with the specified indices
        double[][] ans = new double[index.length][3];
        
        for(int i=0; i<index.length; i++){
            System.arraycopy(allCoords, 3*index[i], ans[i], 0, 3);
        }
        
        return ans;
    }
    
    
    public String getCurResType(){//current residue type
        return res.fullName.substring(0,3);//this is always the first three letters of the full name
    }
    

    @Override
    public void apply(double paramVal) {
        throw new IllegalArgumentException("ERROR: ResidueTypeDOF takes an residue type name"
                + " as argument; can't take "+paramVal);
    }
    
    
    
    @Override
    public Residue getResidue() { return res; }
    
    
    @Override
    public DegreeOfFreedom copy() {
        return new ResidueTypeDOF(templateLib, res);
    }
    
    @Override
    public void setMolecule(Molecule val) {
        res = val.getResByPDBResNumber(res.getPDBResNumber());
    }

    @Override
    public DOFBlock getBlock(){
        return null;
    }

    @Override
    public String getName() {
        return "RESTYPE"+res.getPDBResNumber();
    }
}
