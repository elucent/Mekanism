package mekanism.common.tileentity;

import ic2.api.energy.tile.IEnergySink;

import java.util.ArrayList;

import mekanism.api.EnumColor;
import mekanism.api.Object3D;
import mekanism.api.energy.IStrictEnergyAcceptor;
import mekanism.common.HashList;
import mekanism.common.IActiveState;
import mekanism.common.IRedstoneControl;
import mekanism.common.Mekanism;
import mekanism.common.PacketHandler;
import mekanism.common.PacketHandler.Transmission;
import mekanism.common.block.BlockMachine.MachineType;
import mekanism.common.network.PacketTileEntity;
import mekanism.common.transporter.SlotInfo;
import mekanism.common.transporter.TransporterFilter;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.TransporterUtils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.ForgeDirection;

import com.google.common.io.ByteArrayDataInput;

public class TileEntityLogisticalSorter extends TileEntityElectricBlock implements IRedstoneControl, IActiveState, IEnergySink, IStrictEnergyAcceptor
{
	public HashList<TransporterFilter> filters = new HashList<TransporterFilter>();
	
	public RedstoneControl controlType = RedstoneControl.DISABLED;
	
	public EnumColor color;
	
	public final int MAX_DELAY = 10;
	
	public int delayTicks;
	
	public boolean isActive;
	
	public boolean clientActive;
	
	public final double ENERGY_PER_ITEM = 5;
	
	public TileEntityLogisticalSorter() 
	{
		super("LogisticalSorter", MachineType.LOGISTICAL_SORTER.baseEnergy);
		inventory = new ItemStack[1];
		doAutoSync = false;
	}
	
	@Override
	public void onUpdate()
	{
		super.onUpdate();
		
		if(!worldObj.isRemote)
		{
			delayTicks = Math.max(0, delayTicks-1);
			
			if(delayTicks == 8)
			{
				setActive(false);
			}
			
			if(MekanismUtils.canFunction(this) && delayTicks == 0)
			{
				TileEntity back = Object3D.get(this).getFromSide(ForgeDirection.getOrientation(facing).getOpposite()).getTileEntity(worldObj);
				TileEntity front = Object3D.get(this).getFromSide(ForgeDirection.getOrientation(facing)).getTileEntity(worldObj);
				
				if(back instanceof IInventory && front instanceof TileEntityLogisticalTransporter)
				{
					IInventory inventory = (IInventory)back;
					TileEntityLogisticalTransporter transporter = (TileEntityLogisticalTransporter)front;
					
					SlotInfo inInventory = TransporterUtils.takeItem(inventory, facing);
					
					if(inInventory != null && inInventory.itemStack != null)
					{
						EnumColor filterColor = color;
						
						for(TransporterFilter filter : filters)
						{
							if(filter.canFilter(inInventory.itemStack))
							{
								filterColor = filter.color;
								break;
							}
						}
						
						double needed = getEnergyNeeded(inInventory.itemStack);
						
						if(getEnergy() >= needed && TransporterUtils.insert(this, transporter, inInventory.itemStack, filterColor))
						{
							setEnergy(getEnergy()-getEnergyNeeded(inInventory.itemStack));
							inventory.setInventorySlotContents(inInventory.slotID, null);
							setActive(true);
						}
						else {
							inventory.setInventorySlotContents(inInventory.slotID, inInventory.itemStack);
						}
					}
					
					delayTicks = 10;
				}
			}
			
			if(playersUsing.size() > 0)
			{
				for(EntityPlayer player : playersUsing)
				{
					PacketHandler.sendPacket(Transmission.SINGLE_CLIENT, new PacketTileEntity().setParams(Object3D.get(this), getGenericPacket(new ArrayList())), player);
				}
			}
		}
	}
	
    @Override
    public void writeToNBT(NBTTagCompound nbtTags)
    {
        super.writeToNBT(nbtTags);
        
        nbtTags.setInteger("controlType", controlType.ordinal());
        
        if(color != null)
        {
        	nbtTags.setInteger("color", TransporterUtils.colors.indexOf(color));
        }
        
        NBTTagList filterTags = new NBTTagList();
        
        for(TransporterFilter filter : filters)
        {
        	NBTTagCompound tagCompound = new NBTTagCompound();
        	filter.write(tagCompound);
        	filterTags.appendTag(tagCompound);
        }
        
        if(filterTags.tagCount() != 0)
        {
        	nbtTags.setTag("filters", filterTags);
        }
    }
    
    @Override
    public void readFromNBT(NBTTagCompound nbtTags)
    {
    	super.readFromNBT(nbtTags);
    	
    	controlType = RedstoneControl.values()[nbtTags.getInteger("controlType")];
    	
    	if(nbtTags.hasKey("color"))
    	{
    		color = TransporterUtils.colors.get(nbtTags.getInteger("color"));
    	}
    	
       	if(nbtTags.hasKey("filters"))
    	{
    		NBTTagList tagList = nbtTags.getTagList("filters");
    		
    		for(int i = 0; i < tagList.tagCount(); i++)
    		{
    			filters.add(TransporterFilter.readFromNBT((NBTTagCompound)tagList.tagAt(i)));
    		}
    	}
    }
	
	@Override
	public void handlePacketData(ByteArrayDataInput dataStream)
	{
		if(!worldObj.isRemote)
		{
			if(dataStream.readInt() == 0)
			{
				color = TransporterUtils.increment(color);
			}
			
			return;
		}
		
		super.handlePacketData(dataStream);
		
		int type = dataStream.readInt();
		
		if(type == 0)
		{
			isActive = dataStream.readBoolean();
			controlType = RedstoneControl.values()[dataStream.readInt()];
			
			int c = dataStream.readInt();
			
			if(c != -1)
			{
				color = TransporterUtils.colors.get(c);
			}
			else {
				color = null;
			}
			
			filters.clear();
			
			int amount = dataStream.readInt();
			
			for(int i = 0; i < amount; i++)
			{
				filters.add(TransporterFilter.readFromPacket(dataStream));
			}
			
			MekanismUtils.updateBlock(worldObj, xCoord, yCoord, zCoord);
		}
		else if(type == 1)
		{
			isActive = dataStream.readBoolean();
			controlType = RedstoneControl.values()[dataStream.readInt()];
			
			int c = dataStream.readInt();
			
			if(c != -1)
			{
				color = TransporterUtils.colors.get(c);
			}
			else {
				color = null;
			}
			
			MekanismUtils.updateBlock(worldObj, xCoord, yCoord, zCoord);
		}
		else if(type == 2)
		{
			filters.clear();
			
			int amount = dataStream.readInt();
			
			for(int i = 0; i < amount; i++)
			{
				filters.add(TransporterFilter.readFromPacket(dataStream));
			}
		}
	}
	
	private double getEnergyNeeded(ItemStack itemStack)
	{
		if(itemStack == null)
		{
			return 0;
		}
		
		return itemStack.stackSize*ENERGY_PER_ITEM;
	}
	
	@Override
	public ArrayList getNetworkedData(ArrayList data)
	{
		super.getNetworkedData(data);
		
		data.add(0);
		
		data.add(isActive);
		data.add(controlType.ordinal());
		
		if(color != null)
		{
			data.add(TransporterUtils.colors.indexOf(color));
		}
		else {
			data.add(-1);
		}
		
		data.add(filters.size());
		
		for(TransporterFilter filter : filters)
		{
			filter.write(data);
		}
		
		return data;
	}
	
	public ArrayList getGenericPacket(ArrayList data)
	{
		super.getNetworkedData(data);
		
		data.add(1);
		
		data.add(isActive);
		data.add(controlType.ordinal());
		
		if(color != null)
		{
			data.add(TransporterUtils.colors.indexOf(color));
		}
		else {
			data.add(-1);
		}
		
		return data;
		
	}
	
	public ArrayList getFilterPacket(ArrayList data)
	{
		super.getNetworkedData(data);
		
		data.add(2);
		
		data.add(filters.size());
		
		for(TransporterFilter filter : filters)
		{
			filter.write(data);
		}
		
		return data;
	}
	
	@Override
	public boolean canExtractItem(int slotID, ItemStack itemstack, int side)
	{
		return false;
	}
	
	@Override
	public boolean isItemValidForSlot(int slotID, ItemStack itemstack)
	{
		return false;
	}
	
	@Override
	public int getInventoryStackLimit()
	{
		return 1;
	}
	
	@Override
	public int[] getAccessibleSlotsFromSide(int side) 
	{
		if(side == ForgeDirection.getOrientation(facing).ordinal() || side == ForgeDirection.getOrientation(facing).getOpposite().ordinal())
		{
			return new int[] {0};
		}
		
		return null;
	}

	@Override
	public void openChest()
	{
		if(!worldObj.isRemote)
		{
			PacketHandler.sendPacket(Transmission.CLIENTS_RANGE, new PacketTileEntity().setParams(Object3D.get(this), getFilterPacket(new ArrayList())), Object3D.get(this), 50D);
		}
	}
	
	@Override
	public RedstoneControl getControlType() 
	{
		return controlType;
	}

	@Override
	public void setControlType(RedstoneControl type) 
	{
		controlType = type;
	}
	
	@Override
    public void setActive(boolean active)
    {
    	isActive = active;
    	
    	if(clientActive != active)
    	{
    		PacketHandler.sendPacket(Transmission.ALL_CLIENTS, new PacketTileEntity().setParams(Object3D.get(this), getNetworkedData(new ArrayList())));
    		
    		clientActive = active;
    	}
    }
    
    @Override
    public boolean getActive()
    {
    	return isActive;
    }
    
    @Override
    public boolean hasVisual()
    {
    	return true;
    }

	@Override
	public boolean acceptsEnergyFrom(TileEntity emitter, ForgeDirection direction) 
	{
		return true;
	}

	@Override
	public double transferEnergyToAcceptor(double amount)
	{
    	double rejects = 0;
    	double neededElectricity = getMaxEnergy()-getEnergy();
    	
    	if(amount <= neededElectricity)
    	{
    		electricityStored += amount;
    	}
    	else {
    		electricityStored += neededElectricity;
    		rejects = amount-neededElectricity;
    	}
    	
    	return rejects;
	}

	@Override
	public boolean canReceiveEnergy(ForgeDirection side) 
	{
		return true;
	}

	@Override
	public double demandedEnergyUnits() 
	{
		return (getMaxEnergy() - getEnergy())*Mekanism.TO_IC2;
	}

	@Override
    public double injectEnergyUnits(ForgeDirection direction, double i)
    {
		double givenEnergy = i*Mekanism.FROM_IC2;
    	double rejects = 0;
    	double neededEnergy = getMaxEnergy()-getEnergy();
    	
    	if(givenEnergy < neededEnergy)
    	{
    		electricityStored += givenEnergy;
    	}
    	else if(givenEnergy > neededEnergy)
    	{
    		electricityStored += neededEnergy;
    		rejects = givenEnergy-neededEnergy;
    	}
    	
    	return rejects*Mekanism.TO_IC2;
    }

	@Override
	public int getMaxSafeInput() 
	{
		return 2048;
	}
}